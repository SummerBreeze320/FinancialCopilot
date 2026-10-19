package com.financial.copilot.agent.core.infra.dag.validator;

import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.EvidenceContract;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <h1>确定性金融产物校验器 (DeterministicArtifactValidator)</h1>
 * <p>
 * 纯 Java 规则执行：Schema 检查、文本尺寸安全边界 (500KB)、必填证据链完整性及置信度底线。
 * 不依赖 LLM 推理。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class DeterministicArtifactValidator implements ArtifactValidator {

    private static final int MAX_PAYLOAD_CHARS = 500_000;

    @Override
    public ArtifactValidationResult validate(GraphNode node, Artifact<?> artifact, ExecutionGraph graph) {
        if (artifact == null) {
            return ArtifactValidationResult.failure(List.of("Artifact is null for node: " + (node != null ? node.getNodeId() : "unknown")), List.of());
        }
        if (artifact.payload() == null) {
            return ArtifactValidationResult.failure(List.of("Artifact payload is null for node: " + (node != null ? node.getNodeId() : "unknown")), List.of());
        }

        List<String> errors = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();

        String payloadStr = String.valueOf(artifact.payload());
        if (payloadStr.length() > MAX_PAYLOAD_CHARS) {
            errors.add("Artifact payload length [" + payloadStr.length() + "] exceeds max length " + MAX_PAYLOAD_CHARS);
        }

        EvidenceContract contract = artifact.evidenceContract();
        if (contract != null) {
            if (contract.missingEvidence() != null && !contract.missingEvidence().isEmpty()) {
                missingEvidence.addAll(contract.missingEvidence());
            }
            if (contract.confidence() < 0.60 && artifact.metadata() != null && artifact.metadata().partial()) {
                errors.add("Confidence [" + contract.confidence() + "] too low for partial artifact");
            }
        }

        if (!errors.isEmpty() || !missingEvidence.isEmpty()) {
            return ArtifactValidationResult.failure(errors, missingEvidence);
        }
        return ArtifactValidationResult.success();
    }
}
