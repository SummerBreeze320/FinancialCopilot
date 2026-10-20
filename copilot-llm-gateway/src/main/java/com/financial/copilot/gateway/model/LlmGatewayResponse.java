package com.financial.copilot.gateway.model;

import lombok.Builder;

/**
 * <h1>网关推理响应结果 (LlmGatewayResponse)</h1>
 * <p>封装大模型统一网关返回的文本内容、深度思考链与治理审计度量数据。</p>
 *
 * @param content 模型产出的最终回答文本
 * @param reasoningContent 深度推理思考链内容 (如 DeepSeek-R1 / Gemini 思考过程)
 * @param actualModel 实际执行本次推理的模型标识
 * @param actualProvider 实际承接请求的底层 Provider
 * @param promptTokens 输入 Token 消耗
 * @param completionTokens 输出 Token 消耗
 * @param totalTokens 总 Token 消耗
 * @param latencyMs 本次请求端到端耗时毫秒数
 * @param isFailover 是否触发了故障自愈转移至备用链路
 * @author FinancialCopilot
 */
@Builder
public record LlmGatewayResponse(
        String content,
        String reasoningContent,
        String actualModel,
        String actualProvider,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        long latencyMs,
        boolean isFailover
) {}
