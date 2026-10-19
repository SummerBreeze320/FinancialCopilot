package com.financial.copilot.agent.core.infra.dag.runtime;

import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;

import java.util.function.Consumer;

/**
 * <h1>DAG 节点执行上下文 (NodeExecutionContext)</h1>
 * <p>
 * 封装单节点运行所需的不可变请求身份、会话产物仓库、树状取消令牌、入参依赖以及流式增量 Token 推送消费器。
 * </p>
 *
 * @param request           工作流全局请求
 * @param nodeId            当前节点 ID
 * @param artifacts         产物存储库
 * @param cancellationToken 取消令牌
 * @param attempt           当前执行尝试次数
 * @param input             已解析的上游输入产物映射
 * @param chunkConsumer     流式增量内容 Token 消费器 (可为空)
 */
public record NodeExecutionContext(
        GraphRunRequest request,
        String nodeId,
        ArtifactStore artifacts,
        CancellationToken cancellationToken,
        int attempt,
        NodeInput input,
        Consumer<String> chunkConsumer
) {
    public NodeExecutionContext(GraphRunRequest request, String nodeId, ArtifactStore artifacts, CancellationToken cancellationToken) {
        this(request, nodeId, artifacts, cancellationToken, 1, null, null);
    }

    public NodeExecutionContext(GraphRunRequest request, String nodeId, ArtifactStore artifacts, CancellationToken cancellationToken, int attempt) {
        this(request, nodeId, artifacts, cancellationToken, attempt, null, null);
    }

    public NodeExecutionContext(GraphRunRequest request, String nodeId, ArtifactStore artifacts, CancellationToken cancellationToken, int attempt, NodeInput input) {
        this(request, nodeId, artifacts, cancellationToken, attempt, input, null);
    }
}
