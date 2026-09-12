package com.financial.copilot.agent.core.dag.guard;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.EvidenceContract;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.GraphNode;

import java.util.List;

/**
 * <h1>默认节点质量门禁实现</h1>
 * 基于证据契约完整性 (missingEvidence) 与业务自洽规则做出 PASS / NEED_MORE_DATA / INVALID 三态裁决。
 */
public class DefaultNodeQualityGate implements NodeQualityGate {

    @Override
    public GateVerdict evaluate(GraphNode node, Artifact<?> artifact, ExecutionGraph graph) {
        if (artifact == null || artifact.payload() == null) {
            return GateVerdict.invalid("Artifact or payload is null for node: " + (node != null ? node.getNodeId() : "unknown"));
        }

        EvidenceContract contract = artifact.evidenceContract();
        if (contract != null) {
            List<String> missing = contract.missingEvidence();
            if (missing != null && !missing.isEmpty()) {
                return GateVerdict.needMoreData(missing, "Evidence missing items detected: " + missing);
            }
            if (contract.confidence() < 0.60 && artifact.metadata() != null && artifact.metadata().partial()) {
                return GateVerdict.needMoreData(List.of("confidence_reinforcement"), "Confidence too low for partial artifact: " + contract.confidence());
            }
        }

        return GateVerdict.pass();
    }
}
