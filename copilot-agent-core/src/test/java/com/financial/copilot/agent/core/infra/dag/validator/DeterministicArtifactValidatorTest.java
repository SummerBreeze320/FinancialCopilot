package com.financial.copilot.agent.core.infra.dag.validator;

import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactMetadata;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.artifact.EvidenceContract;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("确定性产物校验器 (DeterministicArtifactValidator) 测试")
class DeterministicArtifactValidatorTest {

    private final DeterministicArtifactValidator validator = new DeterministicArtifactValidator();
    private final ExecutionGraph graph = new ExecutionGraph("g1");
    private final GraphNode node = GraphNode.builder().nodeId("node-1").name("分析").taskType("BATCH_ANALYSIS").build();

    @Test
    @DisplayName("完整产物校验通过")
    void testValidArtifactPasses() {
        Artifact<String> art = Artifact.of(
                "art-1", ArtifactType.FUND_RESEARCH, "node-1", "基金分析详情",
                ArtifactMetadata.standard("FundAnalyzer"),
                EvidenceContract.sufficient("分析结论", List.of("fund://000001"))
        );

        ArtifactValidationResult result = validator.validate(node, art, graph);
        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    @DisplayName("空产物或空载荷被拒绝")
    void testNullArtifactOrPayloadRejected() {
        ArtifactValidationResult resNull = validator.validate(node, null, graph);
        assertThat(resNull.isValid()).isFalse();

        Artifact<Object> resNullPayload = new Artifact<>("art-2", ArtifactType.GENERAL, "node-1", null, null, null);
        ArtifactValidationResult resPayload = validator.validate(node, resNullPayload, graph);
        assertThat(resPayload.isValid()).isFalse();
    }

    @Test
    @DisplayName("产物体积超过上限 (500KB) 被拒绝，防止 OOM 与 Token 溢出")
    void testOversizedPayloadRejected() {
        String hugePayload = "A".repeat(600_000);
        Artifact<String> art = Artifact.of("art-huge", ArtifactType.GENERAL, "node-1", hugePayload);

        ArtifactValidationResult result = validator.validate(node, art, graph);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("exceeds max length"));
    }

    @Test
    @DisplayName("置信度过低且为 partial 产物被拒绝")
    void testLowConfidencePartialRejected() {
        EvidenceContract contract = new EvidenceContract("不确定结论", List.of(), List.of(), List.of("missing_data"), 0.3);
        Artifact<String> art = Artifact.of(
                "art-low", ArtifactType.GENERAL, "node-1", "低置信度内容",
                ArtifactMetadata.partial("FundAnalyzer", List.of("000001"), "缺少数据"),
                contract
        );

        ArtifactValidationResult result = validator.validate(node, art, graph);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getMissingEvidence()).contains("missing_data");
    }
}
