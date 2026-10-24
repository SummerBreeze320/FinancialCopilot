package com.financial.copilot.gateway.model;

import lombok.Builder;

import java.time.Instant;

/**
 * <h1>大模型全链路调用审计记录 (LlmAuditRecord)</h1>
 * <p>网关执行结束后异步沉淀的度量与审计事实数据，用于计费、排障与观测分析。</p>
 *
 * @param traceId 链路追踪唯一定位符
 * @param scene 调用的业务场景
 * @param actualModel 实际调用的模型名称
 * @param actualProvider 承接服务商
 * @param promptTokens 提示词消耗 Token
 * @param completionTokens 补全消耗 Token
 * @param totalTokens 总 Token 数
 * @param latencyMs 调用耗时
 * @param isFailover 是否发生备用降级转移
 * @param errorMessage 失败时的异常描述（成功时为 null）
 * @param timestamp 记录产生时间戳
 * @author FinancialCopilot
 */
@Builder
public record LlmAuditRecord(
        String traceId,
        LlmRoutingScene scene,
        String actualModel,
        String actualProvider,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        long latencyMs,
        boolean isFailover,
        String errorMessage,
        Instant timestamp
) {}
