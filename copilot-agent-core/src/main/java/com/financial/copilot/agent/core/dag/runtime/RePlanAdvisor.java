package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.patch.GraphPatch;

/**
 * <h1>动态改图与变轨顾问接口 (RePlanAdvisor)</h1>
 * <p>
 * 接收节点完成事件及其实体产物，生成增量差分补丁 {@link GraphPatch}。
 * </p>
 */
@FunctionalInterface
public interface RePlanAdvisor {

    /**
     * 为当前完成节点评估并规划图差分补丁
     *
     * @param graph           当前图拓扑
     * @param completedNodeId 已完成的节点 ID
     * @param result          节点执行产物
     * @return 增量变更补丁，若不需要变更返回 null 或空操作补丁
     */
    GraphPatch planPatch(ExecutionGraph graph, String completedNodeId, Artifact<?> result);
}
