package com.financial.copilot.agent.core.infra.dag.validator;

import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;

/**
 * <h1>产物确定性校验器端口</h1>
 *
 * @author FinancialCopilot
 */
public interface ArtifactValidator {
    ArtifactValidationResult validate(GraphNode node, Artifact<?> artifact, ExecutionGraph graph);
}
