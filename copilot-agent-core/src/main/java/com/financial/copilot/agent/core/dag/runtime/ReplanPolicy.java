package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.NodeStatus;

import java.util.List;

/**
 * <h1>动态改图自适应触发策略 (ReplanPolicy)</h1>
 * <p>
 * 决定节点完成时是否需要激活 Planner 生成 GraphPatch，绝不在正常节点盲目调用 LLM 规划。
 * </p>
 */
@FunctionalInterface
public interface ReplanPolicy {

    /**
     * 判断当前节点产物是否需要激活 Planner 生成增量补丁
     */
    boolean shouldReplan(
            ExecutionGraph graph,
            String completedNodeId,
            Artifact<?> result,
            NodeStatus status
    );

    /**
     * 默认规则型启发式策略 (零成本、纳秒级判定，不调 LLM)
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
            // 4. 初筛标的池为空 (无法进行后续分析)
            if (result.payload() instanceof List<?> list && list.isEmpty()) return true;
            return false;
        };
    }

    /**
     * 静态确定性策略（永不改图）
     */
    static ReplanPolicy never() {
        return (graph, nodeId, result, status) -> false;
    }
}
