package com.financial.copilot.agent.core.infra.tool;

/**
 * <h1>工具参数校验异常 (InvalidToolArgumentException)</h1>
 * <p>当 Agent 传入的工具参数违反 {@link ToolConstraint} 约束时抛出，用于触发自省反思循环修正。</p>
 *
 * @author FinancialCopilot
 */
public class InvalidToolArgumentException extends RuntimeException {

    private final String parameterName;
    private final Object rejectedValue;

    public InvalidToolArgumentException(String parameterName, Object rejectedValue, String message) {
        super(message);
        this.parameterName = parameterName;
        this.rejectedValue = rejectedValue;
    }

    public String getParameterName() {
        return parameterName;
    }

    public Object getRejectedValue() {
        return rejectedValue;
    }
}
