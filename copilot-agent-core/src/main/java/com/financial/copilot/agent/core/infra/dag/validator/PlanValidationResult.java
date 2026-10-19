package com.financial.copilot.agent.core.infra.dag.validator;

import java.util.Collections;
import java.util.List;

/**
 * <h1>图规划确定性校验结果</h1>
 *
 * @author FinancialCopilot
 */
public class PlanValidationResult {

    private final boolean valid;
    private final List<String> errors;

    private PlanValidationResult(boolean valid, List<String> errors) {
        this.valid = valid;
        this.errors = errors != null ? List.copyOf(errors) : List.of();
    }

    public static PlanValidationResult success() {
        return new PlanValidationResult(true, List.of());
    }

    public static PlanValidationResult failure(List<String> errors) {
        return new PlanValidationResult(false, errors);
    }

    public boolean isValid() {
        return valid;
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }
}
