package com.financial.copilot.agent.core.llm.provider;

import java.io.Serializable;
import java.util.List;

/**
 * <h1>大模型厂商元数据聚合模型 (LLM Provider Metadata)</h1>
 * <p>
 * 职责：描述大模型厂商的全局接入配置与可用模型矩阵，供研发测试管理控制台查询与按需选型。
 * </p>
 *
 * @param providerType    厂商类型枚举
 * @param displayName     厂商中文名称
 * @param defaultBaseUrl  默认端点 Base URL
 * @param description     厂商特点与场景说明
 * @param officialWebsite 官方文档与控制台网址
 * @param supportedModels 支持的模型选项列表
 * @param defaultModel    默认推荐模型标识
 * @author FinancialCopilot
 */
public record LlmProviderMetadata(
        LlmProviderType providerType,
        String displayName,
        String defaultBaseUrl,
        String description,
        String officialWebsite,
        List<LlmModelOption> supportedModels,
        String defaultModel
) implements Serializable {
}
