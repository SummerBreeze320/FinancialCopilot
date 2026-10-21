package com.financial.copilot.agent.core.infra.supervisor.runtime;

import com.financial.copilot.domain.platform.execution.port.ResearchExecutionPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>Supervisor 运行句柄注册表 (SupervisorRunRegistry)</h1>
 * <p>
 * 按 (userId, runId) 维护活跃运行句柄，提供运行查找、取消控制与生命周期清理。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class SupervisorRunRegistry {

    private record RunKey(Long userId, String runId) {}

    private final ConcurrentHashMap<RunKey, ResearchRunHandle> active = new ConcurrentHashMap<>();
    private final ResearchExecutionPort executionPort;

    public SupervisorRunRegistry() {
        this(null);
    }

    @Autowired
    public SupervisorRunRegistry(@Autowired(required = false) ResearchExecutionPort executionPort) {
        this.executionPort = executionPort;
    }

    public ResearchRunHandle register(Long userId, ResearchRunHandle handle) {
        RunKey key = new RunKey(userId, handle.workflowRunId());
        active.put(key, handle);
        handle.completion().whenComplete((result, error) -> active.remove(key, handle));
        return handle;
    }

    public Optional<ResearchRunHandle> find(Long userId, String runId) {
        return Optional.ofNullable(active.get(new RunKey(userId, runId)));
    }

    public boolean cancel(Long userId, String runId, String reason) {
        ResearchRunHandle handle = active.get(new RunKey(userId, runId));
        if (handle != null) {
            handle.cancel(reason);
            return true;
        }
        return false;
    }
}
