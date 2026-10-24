package com.financial.copilot.agent.core.infra.coordination;

import com.financial.copilot.agent.core.infra.agent.AgentRole;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * <h1>内存态智能体操作幂等账本实现 (InMemoryAgentOperationLedger)</h1>
 * <p>
 * 基于无锁原子竞态与 {@link CompletableFuture} 广播机制，
 * 严格确保具有相同幂等键的调用在多线程下只触发一次业务 Supplier，
 * 其余并发调用安全挂起并等待首个调用的完成通知，杜绝重复计算。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class InMemoryAgentOperationLedger implements AgentOperationLedger {

    private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AgentOperation> ledger = new ConcurrentHashMap<>();

    @Override
    public <T> T executeOnce(
            String idempotencyKey,
            String workflowRunId,
            String agentRunId,
            AgentRole role,
            String operationType,
            Supplier<T> action
    ) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId cannot be null");
        Objects.requireNonNull(agentRunId, "agentRunId cannot be null");
        Objects.requireNonNull(role, "role cannot be null");
        Objects.requireNonNull(operationType, "operationType cannot be null");
        Objects.requireNonNull(action, "action cannot be null");

        CompletableFuture<Object> promise = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(idempotencyKey, promise);

        if (existing == null) {
            // 获得首发执行权
            ledger.put(idempotencyKey, AgentOperation.requested(idempotencyKey, workflowRunId, agentRunId, role, operationType, 0L).toRunning());
            try {
                T result = action.get();
                promise.complete(result);
                String ref = result != null ? result.toString() : "null";
                ledger.computeIfPresent(idempotencyKey, (k, op) -> op.toSucceeded(ref));
                return result;
            } catch (Throwable t) {
                promise.completeExceptionally(t);
                ledger.computeIfPresent(idempotencyKey, (k, op) -> op.toFailed(t.getMessage()));
                if (t instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(t);
            }
        } else {
            // 未获得执行权，直接等待并订阅首发结果
            try {
                @SuppressWarnings("unchecked")
                T result = (T) existing.join();
                return result;
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(cause);
            }
        }
    }

    @Override
    public Optional<AgentOperation> getOperation(String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ledger.get(idempotencyKey));
    }
}
