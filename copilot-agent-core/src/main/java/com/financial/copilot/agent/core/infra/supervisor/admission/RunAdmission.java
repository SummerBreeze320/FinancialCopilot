package com.financial.copilot.agent.core.infra.supervisor.admission;

import com.financial.copilot.agent.core.infra.supervisor.runtime.ExecutionIdentity;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <h1>运行时准入许可 (RunAdmission)</h1>
 * <p>
 * 封装已分配的并发槽位凭证，遵循 {@link AutoCloseable} 规范；
 * 关闭时幂等释放全局容量、用户配额及会话独占槽位。
 * </p>
 *
 * @author FinancialCopilot
 */
public class RunAdmission implements AutoCloseable {

    private final ExecutionIdentity identity;
    private final Runnable releaseCallback;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RunAdmission(ExecutionIdentity identity, Runnable releaseCallback) {
        this.identity = Objects.requireNonNull(identity, "identity cannot be null");
        this.releaseCallback = Objects.requireNonNull(releaseCallback, "releaseCallback cannot be null");
    }

    /**
     * 获取当前许可关联的执行凭证。
     *
     * @return 执行凭证
     */
    public ExecutionIdentity identity() {
        return identity;
    }

    /**
     * 判断当前许可是否已经被释放关闭。
     *
     * @return true 若已释放
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 幂等释放许可与并发计数。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            releaseCallback.run();
        }
    }
}
