package com.financial.copilot.agent.core.dag.guard;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.GraphNode;

import java.util.List;

/**
 * <h1>节点质量门禁与执行防线 (Node Quality Gate / Graph Guard)</h1>
 * 实行三态确定性裁决 (PASS, NEED_MORE_DATA, INVALID)，防止幻觉与缺失毒数据污染下游。
 */
public interface NodeQualityGate {

    /**
     * 评估节点产物质量并给出三态决策
     */
    GateVerdict evaluate(GraphNode node, Artifact<?> artifact, ExecutionGraph graph);

    enum Decision {
        /** 质量合格，证据链完备，放行下游 */
        PASS,
        /** 结论有效但缺失关键支撑证据，需由 Planner 动态插桩补数 */
        NEED_MORE_DATA,
        /** 产物数据非法或违背金融事实约束，需触发 Retry / Fallback */
        INVALID
    }

    record GateVerdict(
        Decision decision,
        String reason,
        List<String> missingEvidence,
        String recommendedAction
    ) {
        public static GateVerdict pass() {
            return new GateVerdict(Decision.PASS, "Evidence and payload valid", List.of(), "CONTINUE");
        }

        public static GateVerdict needMoreData(List<String> missing, String reason) {
            return new GateVerdict(Decision.NEED_MORE_DATA, reason, missing != null ? List.copyOf(missing) : List.of(), "TRIGGER_GRAPH_PATCH");
        }

        public static GateVerdict invalid(String reason) {
            return new GateVerdict(Decision.INVALID, reason, List.of(), "TRIGGER_FAILURE_POLICY");
        }
    }
}
