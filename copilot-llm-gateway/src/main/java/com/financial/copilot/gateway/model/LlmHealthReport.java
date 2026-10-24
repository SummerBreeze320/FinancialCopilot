package com.financial.copilot.gateway.model;

import lombok.Builder;

import java.time.Instant;
import java.util.Map;

/**
 * <h1>大模型网关健康检查报告 (LlmHealthReport)</h1>
 * <p>反映网关自身及各 Provider 连通性探测结果。</p>
 *
 * @param healthy 全局是否健康就绪
 * @param latencyMs 健康探测探测平均耗时毫秒数
 * @param details 各 Provider 健康细节字典
 * @param reportTime 报告生成时间
 * @author FinancialCopilot
 */
@Builder
public record LlmHealthReport(
        boolean healthy,
        long latencyMs,
        Map<String, Boolean> details,
        Instant reportTime
) {}
