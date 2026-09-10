package com.financial.copilot.agent.core.llm.provider;

import java.io.Serializable;

/**
 * <h1>大模型单项模型选项规格 (LLM Model Option)</h1>
 * <p>
 * 职责：描述具体模型的技术规格，包括模型 ID、展示名称、上下文窗口长度、是否支持思维链深度推理及流式推送。
 * </p>
 *
 * @param modelId           模型唯一标识符（例如 deepseek-reasoner, gpt-4o 等）
 * @param displayName       前端展示名称
 * @param description       技术特点与场景描述
 * @param contextWindow     上下文窗口大小 (如 64k, 128k)
 * @param supportsReasoning 是否为具备 CoT 深度思考能力的推理模型 (如 o1, R1)
 * @param supportsStreaming 是否支持 SSE 流式实时输出
 * @author FinancialCopilot
 */
public record LlmModelOption(
        String modelId,
        String displayName,
        String description,
        String contextWindow,
        boolean supportsReasoning,
        boolean supportsStreaming
) implements Serializable {
}
