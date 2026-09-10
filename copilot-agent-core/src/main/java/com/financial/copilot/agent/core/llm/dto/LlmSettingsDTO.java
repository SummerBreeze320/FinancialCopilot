package com.financial.copilot.agent.core.llm.dto;

import com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel;
import com.financial.copilot.agent.core.llm.provider.LlmProviderType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * <h1>大模型调用配置与性能参数传输对象 (LLM Settings DTO)</h1>
 * <p>
 * 职责：封装单次调用或系统生效的模型参数。
 * 针对金融终端客户，仅需指定 {@link #performanceLevel}（投研深度 HIGH/MIDDLE/LOW）；
 * 针对研发测试管理控制台，可指定底层 {@link #provider}、{@link #model}、{@link #customBaseUrl} 等详细配置。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmSettingsDTO implements Serializable {

    /**
     * 厂商类型标识（如 DEEPSEEK, OPENAI, QWEN 等，内部研发管理端可用）
     */
    @Builder.Default
    private LlmProviderType provider = LlmProviderType.DEEPSEEK;

    /**
     * 模型名称标识（如 deepseek-chat, deepseek-reasoner 等，内部研发管理端可用）
     */
    @Builder.Default
    private String model = "deepseek-chat";

    /**
     * 投研性能/思考强度档位（面向客户及系统默认，支持 HIGH/MIDDLE/LOW）
     */
    @Builder.Default
    private LlmPerformanceLevel performanceLevel = LlmPerformanceLevel.MIDDLE;

    /**
     * 采样温度 (0.0 ~ 2.0，若为空则由 performanceLevel 自动决定)
     */
    private Double temperature;

    /**
     * 核采样率 (0.1 ~ 1.0)
     */
    private Double topP;

    /**
     * 最大生成 Token 数 (若为空则由 performanceLevel 自动决定)
     */
    private Integer maxTokens;

    /**
     * 自定义端点 Base URL（研发测试私有化端点使用）
     */
    private String customBaseUrl;

    /**
     * 自定义 API Key（研发测试临时密钥调试使用）
     */
    private String customApiKey;

    /**
     * 获取最终生效的采样温度
     *
     * @return 最终温度数值
     */
    public double resolveTemperature() {
        if (temperature != null) {
            return temperature;
        }
        return performanceLevel != null ? performanceLevel.getDefaultTemperature() : 0.2;
    }

    /**
     * 获取最终生效的最大 Token 数
     *
     * @return 最终 Token 预算
     */
    public int resolveMaxTokens() {
        if (maxTokens != null) {
            return maxTokens;
        }
        return performanceLevel != null ? performanceLevel.getDefaultMaxTokens() : 4096;
    }

    /**
     * 获取官方推理预算字段（用于 o1/o3/R1 等思维链模型）
     *
     * @return "low", "medium" 或 "high"
     */
    public String resolveReasoningEffort() {
        return performanceLevel != null ? performanceLevel.getReasoningEffort() : "medium";
    }
}
