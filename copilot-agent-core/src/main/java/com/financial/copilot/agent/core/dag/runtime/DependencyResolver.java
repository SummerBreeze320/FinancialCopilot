package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.NodeStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * <h1>DAG 依赖拓扑判定裁决器</h1>
 * 纯内存、纳秒级判定前驱依赖是否全部就绪放行。
 */
public class DependencyResolver {

    /**
     * 判断目标节点的前驱依赖是否已全部处于终止成功或跳过状态
     */
    public static boolean isReady(String nodeId, ExecutionGraph graph, Function<String, NodeStatus> statusProvider) {
        Set<String> upstreams = graph.getUpstream(nodeId);
        if (upstreams.isEmpty()) {
            return true;
        }
        for (String upId : upstreams) {
            NodeStatus s = statusProvider.apply(upId);
            if (s != NodeStatus.SUCCEEDED && s != NodeStatus.SKIPPED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 当某节点完成后，检索其所有直接下游中当前已完全就绪 (可进入 READY) 的节点列表
     */
    public static List<String> findReadyChildren(String completedNodeId, ExecutionGraph graph, Function<String, NodeStatus> statusProvider) {
        List<String> readyChildren = new ArrayList<>();
        Set<String> downstreams = graph.getDownstream(completedNodeId);

        for (String childId : downstreams) {
            NodeStatus currentStatus = statusProvider.apply(childId);
            if (currentStatus == NodeStatus.PENDING) {
                if (isReady(childId, graph, statusProvider)) {
                    readyChildren.add(childId);
                }
            }
        }
        return readyChildren;
    }
}
