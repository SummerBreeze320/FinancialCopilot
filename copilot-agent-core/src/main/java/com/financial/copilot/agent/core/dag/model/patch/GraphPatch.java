package com.financial.copilot.agent.core.dag.model.patch;

import java.util.List;

/**
 * <h1>DAG 增量差分补丁模型 (Graph Patch)</h1>
 * Planner 在节点完成后只输出精准的增量操作，禁止整图覆写！
 */
public record GraphPatch(
    int baseRevision,
    List<GraphOperation> operations
) {
    public static GraphPatch of(int baseRevision, GraphOperation... ops) {
        return new GraphPatch(baseRevision, List.of(ops));
    }

    public static GraphPatch of(int baseRevision, List<GraphOperation> operations) {
        return new GraphPatch(baseRevision, operations != null ? List.copyOf(operations) : List.of());
    }
}
