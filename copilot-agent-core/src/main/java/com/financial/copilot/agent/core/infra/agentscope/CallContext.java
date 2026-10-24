package com.financial.copilot.agent.core.infra.agentscope;

import com.financial.copilot.agent.core.infra.agentscope.loop.AgentLoopSupervisor;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import io.agentscope.core.message.ToolResultBlock;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>单次智能体调用运行时作用域上下文实体 (CallContext)</h1>
 * <p>
 * 封装单次请求/节点执行中的瞬态数据（执行上下文、工具观测快照、死循环守护器与观测去重集合），
 * 彻底将运行期状态从 {@link io.agentscope.core.ReActAgent} 实例中剥离，实现智能体单例无状态化。
 * </p>
 *
 * @author FinancialCopilot
 */
public record CallContext(
        NodeExecutionContext executionContext,
        Map<String, List<ToolResultBlock>> observations,
        AgentLoopSupervisor loopSupervisor,
        Set<String> observedIds
) {
    public CallContext {
        Objects.requireNonNull(executionContext, "executionContext cannot be null");
        Objects.requireNonNull(observations, "observations cannot be null");
        Objects.requireNonNull(loopSupervisor, "loopSupervisor cannot be null");
        observedIds = observedIds != null ? observedIds : ConcurrentHashMap.newKeySet();
    }

    public CallContext(
            NodeExecutionContext executionContext,
            Map<String, List<ToolResultBlock>> observations,
            AgentLoopSupervisor loopSupervisor
    ) {
        this(executionContext, observations, loopSupervisor, ConcurrentHashMap.newKeySet());
    }
}
