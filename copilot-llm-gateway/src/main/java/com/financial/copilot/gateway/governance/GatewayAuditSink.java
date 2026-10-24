package com.financial.copilot.gateway.governance;

import com.financial.copilot.gateway.model.LlmAuditRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * <h1>全链路模型调用审计收集器 (GatewayAuditSink)</h1>
 * <p>异步汇总模型调用度量数据，提供内存环形缓冲观测，并广播给业务计费或外部监控监听器。</p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class GatewayAuditSink {

    private final int bufferCapacity;
    private final Deque<LlmAuditRecord> ringBuffer = new ConcurrentLinkedDeque<>();
    private final List<Consumer<LlmAuditRecord>> listeners = new CopyOnWriteArrayList<>();

    public GatewayAuditSink() {
        this(200);
    }

    public GatewayAuditSink(int bufferCapacity) {
        this.bufferCapacity = Math.max(10, bufferCapacity);
    }

    /**
     * 记录一笔模型审计事件并通知所有监听者
     *
     * @param record 审计记录
     */
    public void record(LlmAuditRecord record) {
        if (record == null) {
            return;
        }

        ringBuffer.addFirst(record);
        while (ringBuffer.size() > bufferCapacity) {
            ringBuffer.pollLast();
        }

        for (Consumer<LlmAuditRecord> listener : listeners) {
            try {
                listener.accept(record);
            } catch (Exception e) {
                log.error("[GatewayAuditSink] 审计监听器执行异常: error={}", e.getMessage(), e);
            }
        }
    }

    /**
     * 注册审计事件监听回调
     *
     * @param listener 监听函数
     */
    public void addAuditListener(Consumer<LlmAuditRecord> listener) {
        if (listener != null) {
            this.listeners.add(listener);
        }
    }

    /**
     * 获取最近记录的审计事件列表（时间倒序）
     *
     * @param limit 获取条数上限
     * @return 审计记录列表快照
     */
    public List<LlmAuditRecord> recentRecords(int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        List<LlmAuditRecord> snapshot = new ArrayList<>();
        int count = 0;
        for (LlmAuditRecord record : ringBuffer) {
            snapshot.add(record);
            if (++count >= limit) {
                break;
            }
        }
        return Collections.unmodifiableList(snapshot);
    }
}
