package com.financial.copilot.agent.core.llm.provider;

import lombok.Getter;

/**
 * <h1>支持的大模型服务厂商类型枚举 (LLM Provider Type)</h1>
 * <p>
 * 职责：定义系统预置支持的大模型供应商体系，包含国内外主流商用大模型及本地私有化引擎。
 * </p>
 *
 * @author FinancialCopilot
 */
@Getter
public enum LlmProviderType {

    /**
     * DeepSeek (深度求索 - 生产主力/高性价比)
     */
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),

    /**
     * OpenAI (研发评测黄金基准)
     */
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o"),

    /**
     * 阿里通义千问 (百炼 - 国内信创与云上合规部署选型)
     */
    QWEN("Qwen (通义千问)", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),

    /**
     * 智谱清言 (GLM - 中文金融语料备选)
     */
    ZHIPU("Zhipu (智谱清言)", "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus"),

    /**
     * Ollama (本地私有化离线部署)
     */
    OLLAMA("Ollama (本地离线)", "http://localhost:11434/v1", "deepseek-r1:8b"),

    /**
     * 自定义兼容端点 (兼容 OpenAI / vLLM / OneAPI 网关)
     */
    CUSTOM("Custom (自定义兼容)", "", "");

    /**
     * 厂商展示名称
     */
    private final String displayName;

    /**
     * 官方默认 Base URL
     */
    private final String defaultBaseUrl;

    /**
     * 默认推荐模型名称
     */
    private final String defaultModel;

    LlmProviderType(String displayName, String defaultBaseUrl, String defaultModel) {
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
    }

    /**
     * 安全根据名称解析厂商类型，默认回退至 DEEPSEEK
     *
     * @param name 厂商标识字符串
     * @return 对应的厂商类型
     */
    public static LlmProviderType fromString(String name) {
        if (name == null || name.isBlank()) {
            return DEEPSEEK;
        }
        for (LlmProviderType type : values()) {
            if (type.name().equalsIgnoreCase(name.trim())) {
                return type;
            }
        }
        return DEEPSEEK;
    }
}
