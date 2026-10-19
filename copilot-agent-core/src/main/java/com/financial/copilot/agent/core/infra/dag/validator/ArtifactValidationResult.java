package com.financial.copilot.agent.core.infra.dag.validator;

import java.util.Collections;
import java.util.List;

/**
 * <h1>产物确定性校验结果</h1>
 *
 * @author FinancialCopilot
 */
public class ArtifactValidationResult {

    private final boolean valid;
    private final List<String> errors;
    private final List<String> missingEvidence;

    private ArtifactValidationResult(boolean valid, List<String> errors, List<String> missingEvidence) {
        this.valid = valid;
        this.errors = errors != null ? List.copyOf(errors) : List.of();
        this.missingEvidence = missingEvidence != null ? List.copyOf(missingEvidence) : List.of();
    }

    public static ArtifactValidationResult success() {
        return new ArtifactValidationResult(true, List.of(), List.of());
    }

    public static ArtifactValidationResult failure(List<String> errors, List<String> missingEvidence) {
        return new ArtifactValidationResult(false, errors, missingEvidence);
    }

    public boolean isValid() {
        return valid;
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    public List<String> getMissingEvidence() {
        return Collections.unmodifiableList(missingEvidence);
    }
}
