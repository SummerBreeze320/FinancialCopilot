package com.financial.copilot.gateway.config;

import com.financial.copilot.gateway.catalog.LlmProviderType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * <h1>多厂商大模型基础配置属性 (LLM Configuration Properties)</h1>
 * <p>
 * 职责：映射 Spring 配置文件中的 {@code copilot.llm} 命名空间，定义系统默认大模型厂商、标准模型、深度推理模型与连接信息。
 * 默认优先配置本地独立运行的 Codex-Bridge 服务 (http://127.0.0.1:9090/v1)。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "copilot.llm")
public class LlmProperties {

    /**
     * 默认大模型厂商（默认 DeepSeek）
     */
    private LlmProviderType defaultProvider = LlmProviderType.DEEPSEEK;

    /**
     * 默认标准对话模型名称（默认 deepseek-chat）
     */
    private String defaultModel = "deepseek-chat";

    /**
     * 默认深度思考推理模型名称（默认 deepseek-reasoner）
     */
    private String defaultReasoningModel = "deepseek-reasoner";

    /**
     * 默认是否开启深度思考推理模式（默认 false，极速标准模式）
     */
    private boolean defaultEnableThinking = false;

    /**
     * 默认 Base URL（优先遵循本地 Codex-Bridge 标准）
     */
    private String baseUrl = "http://127.0.0.1:9090/v1";

    /**
     * 默认 API 访问密钥
     */
    private String apiKey = "local-agy";

    /**
     * 各厂商独立配置覆盖字典 (Key 为厂商名称小写，如 deepseek, openai, qwen)
     */
    private Map<String, VendorConfig> vendors = new HashMap<>();

    /**
     * 单个厂商具体连接配置
     */
    @Data
    public static class VendorConfig {
        private String baseUrl;
        private String apiKey;
        private String defaultModel;
        private String defaultReasoningModel;
    }
}
