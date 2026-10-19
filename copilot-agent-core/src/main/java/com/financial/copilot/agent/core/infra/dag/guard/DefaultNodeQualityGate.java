package com.financial.copilot.agent.core.infra.dag.guard;

import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.validator.ArtifactValidationResult;
import com.financial.copilot.agent.core.infra.dag.validator.DeterministicArtifactValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * <h1>默认节点质量门禁实现</h1>
 * 委托至确定性产物校验器 {@link DeterministicArtifactValidator} 做出 PASS / NEED_MORE_DATA / INVALID 三态裁决。
 *
 * @author FinancialCopilot
 */
@Component
public class DefaultNodeQualityGate implements NodeQualityGate {

    private final DeterministicArtifactValidator validator;

    public DefaultNodeQualityGate() {
        this(new DeterministicArtifactValidator());
    }

    @Autowired
    public DefaultNodeQualityGate(@Autowired(required = false) DeterministicArtifactValidator validator) {
        this.validator = validator != null ? validator : new DeterministicArtifactValidator();
    }

    @Override
    public GateVerdict evaluate(GraphNode node, Artifact<?> artifact, ExecutionGraph graph) {
        ArtifactValidationResult result = validator.validate(node, artifact, graph);
        if (result.isValid()) {
            return GateVerdict.pass();
        }
        if (!result.getMissingEvidence().isEmpty()) {
            return GateVerdict.needMoreData(result.getMissingEvidence(), "Evidence missing items detected: " + result.getMissingEvidence());
        }
        String errorMsg = result.getErrors().isEmpty() ? "Validation failed" : String.join("; ", result.getErrors());
        return GateVerdict.invalid(errorMsg);
    }
}
