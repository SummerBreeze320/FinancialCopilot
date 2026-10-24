package com.financial.copilot.agent.core.infra.tool.reflection;

/**
 * <h1>工具自省反思反馈结果 (ToolReflectionResult)</h1>
 *
 * @param shouldRetry 是否允许 Agent 继续重试修正
 * @param currentRetryCount 当前重试累计次数
 * @param feedbackObservation 返回给 Agent 的结构化 Observation 观察提示词
 * @param fallbackResponse 熔断时的安全降级兜底响应
 *
 * @author FinancialCopilot
 */
public record ToolReflectionResult(
        boolean shouldRetry,
        int currentRetryCount,
        String feedbackObservation,
        String fallbackResponse
) {}
