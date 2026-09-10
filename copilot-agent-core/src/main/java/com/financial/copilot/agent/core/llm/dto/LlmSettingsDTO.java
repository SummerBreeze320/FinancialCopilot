package com.financial.copilot.agent.core.llm.dto;

import com.financial.copilot.agent.core.llm.provider.LlmProviderType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * <h1>大模型调用配置与运行参数传输对象 (LLM Settings DTO)</h1>
 * <p>
 * 职责：封装单次调用或系统生效的模型参数。
 * <ul>
 *   <li>面向金融终端客户：仅暴露 {@link #enableThinking} 单一开关（开启深度思考/极速标准模式），对齐行业双模型路由体验；</li>
 *   <li>面向研发测试管理端：可细粒度指定底层 {@link #provider}、标准模型 {@link #model}、推理模型 {@link #reasoningModel}、{@link #customBaseUrl} 等。</li>
 * </ul>
 * 严格遵循真实大模型 API 约束（如 o1/o3/R1 禁止或忽略自定义 temperature/top_p）。
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
     * 标准快速对话模型标识（如 deepseek-chat, gpt-4o 等，内部研发管理端可用）
     */
    @Builder.Default
    private String model = "deepseek-chat";

    /**
     * 深度思考推理模型标识（如 deepseek-reasoner, o3-mini 等，内部研发管理端可用）
     */
    @Builder.Default
    private String reasoningModel = "deepseek-reasoner";

    /**
     * 是否开启深度思考推理模式（面向客户端的一键开关，false: 标准模型，true: 推理模型）
     */
    @Builder.Default
    private boolean enableThinking = false;

    /**
     * 采样温度 (0.0 ~ 2.0，仅对标准对话模型生效；推理模型会自动忽略或置空以避免 400 错误)
     */
    private Double temperature;

    /**
     * 核采样率 (0.1 ~ 1.0)
     */
    private Double topP;

    /**
     * 最大生成 Token 数 (若为空则按模型类型自动赋予默认预算)
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
     * 判断指定模型标识是否为深度推理模型
     *
     * @param modelName 待判断的模型名称
     * @return true 若为推理模型（如 o1, o3, deepseek-reasoner, qwq 等）
     */
    public static boolean isReasoningModel(String modelName) {
        if (modelName == null) {
            return false;
        }
        String lower = modelName.toLowerCase();
        return lower.contains("reasoner")
                || lower.contains("o1")
                || lower.contains("o3")
                || lower.contains("qwq")
                || lower.contains("zero");
    }

    /**
     * 根据是否开启思考模式及当前配置解析最终生效的模型标识
     *
     * @return 最终实际调用的模型名称
     */
    public String resolveEffectiveModel() {
        if (enableThinking && reasoningModel != null && !reasoningModel.isBlank()) {
            return reasoningModel;
        }
        return (model != null && !model.isBlank()) ? model : "deepseek-chat";
    }

    /**
     * 获取最终生效的采样温度。
     * <p>
     * 注意：对于推理模型（如 OpenAI o1/o3-mini 或 DeepSeek-R1），返回 null 以防止发送非官方支持参数导致 HTTP 400。
     * </p>
     *
     * @return 最终温度数值，推理模型返回 null
     */
    public Double resolveTemperature() {
        if (isReasoningModel(resolveEffectiveModel())) {
            return null;
        }
        return temperature != null ? temperature : 0.2;
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
        return enableThinking ? 8192 : 4096;
    }
}
