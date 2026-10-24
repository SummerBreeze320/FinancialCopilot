package com.financial.copilot.gateway.model;

import lombok.Builder;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * <h1>网关推理请求载体 (LlmGatewayRequest)</h1>
 * <p>封装业务层向 LLM 网关发起的统一请求参数。</p>
 *
 * @param systemPrompt 系统提示词
 * @param userPrompt 用户输入提示词
 * @param scene 业务路由场景
 * @param preferredModel 用户或业务指定的偏好模型（为空时由网关按场景智能调度）
 * @param temperature 采样温度系数
 * @param maxTokens 最大生成 Token 数
 * @param timeout 请求超时时限
 * @param metadata 扩展上下文元数据 (包含 userId、traceId 等)
 * @param auditCallback 审计回调句柄 (可为空)
 * @author FinancialCopilot
 */
@Builder
public record LlmGatewayRequest(
        String systemPrompt,
        String userPrompt,
        LlmRoutingScene scene,
        String preferredModel,
        Double temperature,
        Integer maxTokens,
        Duration timeout,
        Map<String, Object> metadata,
        Consumer<LlmAuditRecord> auditCallback
) {}
