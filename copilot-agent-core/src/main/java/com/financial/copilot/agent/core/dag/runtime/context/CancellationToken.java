package com.financial.copilot.agent.core.dag.runtime.context;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <h1>结构化并发树状取消令牌 (Hierarchical CancellationToken)</h1>
 * <p>
 * 支持父子级联向下传播、虚拟线程自动中断绑定、物理资源中止回调与完全无泄漏清理。
 * </p>
 */
public class CancellationToken {

    private final String scopeId;
    private final CancellationToken parent;
    private final Set<CancellationToken> children = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();
    private final Set<Thread> boundThreads = ConcurrentHashMap.newKeySet();
    private volatile String reason;

    public CancellationToken(String scopeId) {
        this(scopeId, null);
    }

    private CancellationToken(String scopeId, CancellationToken parent) {
        this.scopeId = Objects.requireNonNull(scopeId, "scopeId cannot be null");
        this.parent = parent;
    }

    /**
     * 创建受当前生命周期严格约束的子令牌
     */
    public CancellationToken createChild(String childScopeId) {
        CancellationToken child = new CancellationToken(childScopeId, this);
        if (this.cancelled.get()) {
            child.cancel(this.reason);
        } else {
            children.add(child);
            child.onCancel(() -> children.remove(child));
        }
        return child;
    }

    /**
     * 绑定当前执行虚拟线程，在取消发生时自动 interrupt
     */
    public AutoCloseable bindCurrentThread() {
        Thread t = Thread.currentThread();
        boundThreads.add(t);
        if (cancelled.get()) {
            t.interrupt();
        }
        return () -> boundThreads.remove(t);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public String getReason() {
        return reason;
    }

    public String getScopeId() {
        return scopeId;
    }

    public void throwIfCancelled() throws CancellationException {
        if (isCancelled()) {
            throw new CancellationException("Scope [" + scopeId + "] cancelled: " + reason);
        }
    }

    public void onCancel(Runnable callback) {
        if (callback == null) return;
        if (cancelled.get()) {
            try {
                callback.run();
            } catch (Exception ignored) {}
        } else {
            callbacks.add(callback);
        }
    }

    /**
     * 主动触发取消：中断绑定的虚拟线程、触发回调并递归级联取消子作用域
     */
    public void cancel(String reason) {
        if (cancelled.compareAndSet(false, true)) {
            this.reason = reason;

            // 1. 中断绑定的工作线程
            for (Thread t : boundThreads) {
                try {
                    t.interrupt();
                } catch (Exception ignored) {}
            }
            boundThreads.clear();

            // 2. 执行自身注册的物理清理回调 (如释放 Semaphore、取消 HTTP 连接)
            for (Runnable callback : callbacks) {
                try {
                    callback.run();
                } catch (Exception ignored) {}
            }
            callbacks.clear();

            // 3. 向下树状级联触发全部子作用域
            for (CancellationToken child : children) {
                child.cancel(reason);
            }
            children.clear();
        }
    }
}
