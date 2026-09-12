package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.payload.FundPool;
import com.financial.copilot.agent.core.dag.artifact.payload.FundResearchResult;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.model.NodeStatus;

import java.util.List;

/**
 * <h1>动态改图自适应触发策略 (ReplanPolicy)</h1>
 * <p>
 * 决定节点完成时是否需要激活 Planner 生成 GraphPatch，绝不在正常节点盲目调用 LLM 规划。
 * </p>
 *
 * @author FinancialCopilot
 */
@FunctionalInterface
public interface ReplanPolicy {

    /**
     * 判断当前节点产物是否需要激活 Planner 生成增量补丁
     *
     * @param graph           当前执行图
     * @param completedNodeId 刚完成的节点 ID
     * @param result          产物
     * @param status          节点最终执行状态
     * @return true 若需唤醒规划器重规划
     */
    boolean shouldReplan(
            ExecutionGraph graph,
            String completedNodeId,
            Artifact<?> result,
            NodeStatus status
    );

    /**
     * 默认规则型启发式策略 (零成本、纳秒级判定，不调 LLM)
     *
     * @return 启发式策略实例
     */
    static ReplanPolicy heuristic() {
        return (graph, nodeId, result, status) -> {
            // 1. 节点失败
            if (status == NodeStatus.FAILED) return true;
            if (result == null) return false;

            // 2. 证据契约声明不足
            if (result.evidenceContract() != null && !result.evidenceContract().isSufficient()) return true;

            // 3. 产物明确标记为部分缺失/降级
            if (result.metadata() != null && result.metadata().partial()) return true;

            // 4. 初筛标的池为空 (无法进行后续体检分析)
            if (result.payload() instanceof List<?> list && list.isEmpty()) return true;
            if (result.payload() instanceof FundPool pool && pool.isEmpty()) return true;

            // 5. 候选标的仅有 1 只且下游存在 COMPARISON 节点 (需动态自适应降级为单标的深度剖析)
            if (result.payload() instanceof FundResearchResult research) {
                if (research.topCandidates() != null && research.topCandidates().size() == 1 && graph != null) {
                    for (String downId : graph.getDownstream(nodeId)) {
                        GraphNode downNode = graph.getNode(downId);
                        if (downNode != null && "COMPARISON".equalsIgnoreCase(downNode.getTaskType())) {
                            return true;
                        }
                    }
                }
            }

            return false;
        };
    }

    /**
     * 静态确定性策略（永不改图）
     *
     * @return 永不改图策略实例
     */
    static ReplanPolicy never() {
        return (graph, nodeId, result, status) -> false;
    }
}
