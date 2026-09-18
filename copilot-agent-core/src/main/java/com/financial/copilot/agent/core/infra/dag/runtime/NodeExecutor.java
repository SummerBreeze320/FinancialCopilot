package com.financial.copilot.agent.core.infra.dag.runtime;

import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;

/**
 * <h1>DAG 节点执行器适配接口</h1>
 * 负责桥接执行单个节点对应的 Agent 局部 ReAct 或工具调用。
 */
@FunctionalInterface
public interface NodeExecutor {

    /**
     * 执行指定图节点并生成强类型产物
     *
     * @param node              当前执行节点
     * @param input             根据节点 InputBinding 解析出的精确输入
     * @param context           当前运行与节点执行上下文
     * @return 节点产物实体
     * @throws Exception 执行异常
     */
    Artifact<?> execute(GraphNode node, NodeInput input, NodeExecutionContext context) throws Exception;
}
