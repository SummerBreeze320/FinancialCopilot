package com.financial.copilot.agent.core.infra.dag.runtime;

import com.financial.copilot.domain.platform.execution.port.ResearchExecutionPort;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>图运行实例注册表与本地句柄缓存 (GraphRunRegistry)</h1>
 * <p>
 * 维护本节点活跃运行句柄的快速本地缓存，同时通过 {@link ResearchExecutionPort} 支撑分布式集群下的
 * 跨实例运行归属解析与取消意图持久化落盘。
 * </p>
 *
 * @author FinancialCopilot
 */
public class GraphRunRegistry {

    private record RunKey(Long userId, String runId) {}

    private final ConcurrentHashMap<RunKey, GraphRunHandle> active = new ConcurrentHashMap<>();
    private final ResearchExecutionPort executionPort;

    public GraphRunRegistry() {
        this(null);
    }

    public GraphRunRegistry(ResearchExecutionPort executionPort) {
        this.executionPort = executionPort;
    }

    /**
     * 注册本地活跃运行句柄
     *
     * @param userId 用户 ID
     * @param handle 运行句柄
     * @return 注册的运行句柄
     */
    public GraphRunHandle register(Long userId, GraphRunHandle handle) {
        active.put(new RunKey(userId, handle.runId()), handle);
        handle.completion().whenComplete((result, error) -> active.remove(new RunKey(userId, handle.runId()), handle));
        return handle;
    }

    /**
     * 查询本地活跃运行句柄
     *
     * @param userId 用户 ID
     * @param runId  运行 ID
     * @return 运行句柄 Optional
     */
    public Optional<GraphRunHandle> find(Long userId, String runId) {
        return Optional.ofNullable(active.get(new RunKey(userId, runId)));
    }

    /**
     * 取消运行。先持久化取消意图至数据库，再尝试触发本地句柄取消。
     *
     * @param userId 用户 ID
     * @param runId  运行 ID
     * @param reason 取消原因
     * @return 是否成功触发本地取消或成功持久化取消标记
     */
    public boolean cancel(Long userId, String runId, String reason) {
        boolean persistentCancelled = false;
        if (executionPort != null) {
            try {
                UUID runUuid = UUID.fromString(runId);
                persistentCancelled = executionPort.cancelRun(userId, runUuid, reason, Instant.now());
            } catch (Exception ignored) {
                // runId 可能非 UUID 格式或持久化已终态
            }
        }
        GraphRunHandle handle = active.get(new RunKey(userId, runId));
        if (handle != null) {
            handle.cancel(reason);
            return true;
        }
        return persistentCancelled;
    }
}
