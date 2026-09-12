package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.context.CancellationToken;

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
     * @param artifactStore     全局产物总线 (获取上游输入)
     * @param cancellationToken 作用域取消令牌
     * @return 节点产物实体
     * @throws Exception 执行异常
     */
    Artifact<?> execute(GraphNode node, ArtifactStore artifactStore, CancellationToken cancellationToken) throws Exception;
}
