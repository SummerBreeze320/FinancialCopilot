package com.financial.copilot.agent.core.infra.supervisor.coordination;

import com.financial.copilot.agent.core.infra.supervisor.agent.AgentRole;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * <h1>智能体操作幂等账本接口 (AgentOperationLedger)</h1>
 * <p>
 * 保证在相同 Run 内拥有相同幂等键的调用仅执行一次，并发请求订阅已有执行结果，彻底避免重复工具开销与副作用。
 * </p>
 */
public interface AgentOperationLedger {

    /**
     * 依据幂等键执行动作，若并发竞争或已存在，则订阅已有结果
     *
     * @param idempotencyKey 服务端生成的强幂等键
     * @param workflowRunId 工作流执行 ID
     * @param agentRunId 单次 Agent 执行 ID
     * @param role Agent 角色
     * @param operationType 操作或工具名
     * @param action 实际执行的业务 Supplier
     * @param <T> 返回值类型
     * @return 业务执行结果
     */
    <T> T executeOnce(
            String idempotencyKey,
            String workflowRunId,
            String agentRunId,
            AgentRole role,
            String operationType,
            Supplier<T> action
    );

    /**
     * 根据幂等键查询操作账本记录
     *
     * @param idempotencyKey 幂等键
     * @return 账本记录 Optional
     */
    Optional<AgentOperation> getOperation(String idempotencyKey);
}
