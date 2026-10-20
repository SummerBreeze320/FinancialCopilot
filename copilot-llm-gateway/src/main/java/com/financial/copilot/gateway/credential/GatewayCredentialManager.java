package com.financial.copilot.gateway.credential;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * <h1>网关凭据与密钥安全管理器 (GatewayCredentialManager)</h1>
 * <p>
 * 职责：
 * 1. 统一管理多厂商端点与访问密钥，优先级遵循：环境变量 > 配置文件 > 缺省默认值；
 * 2. 默认优先回退至本地 Codex-Bridge (http://127.0.0.1:9090/v1, local-agy)；
 * 3. 对所有日志、审计与诊断报告提供刚性 API Key 脱敏掩码能力。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class GatewayCredentialManager {

    private final GatewayCredentialProperties properties;

    @Autowired
    public GatewayCredentialManager(GatewayCredentialProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * 获取指定 Provider 的 BaseUrl
     *
     * @param provider 厂商/通道标识 (如 local, deepseek, qwen)
     * @return 端点地址
     */
    public String getBaseUrl(String provider) {
        if ("default".equalsIgnoreCase(provider) || "local".equalsIgnoreCase(provider) || provider == null) {
            String envUrl = System.getenv("OPENAI_BASE_URL");
            if (envUrl != null && !envUrl.isBlank()) {
                return envUrl;
            }
            return properties.getDefaultBaseUrl();
        }

        GatewayCredentialProperties.ProviderCredential cred = properties.getProviders().get(provider);
        if (cred != null && cred.getBaseUrl() != null && !cred.getBaseUrl().isBlank()) {
            return cred.getBaseUrl();
        }
        return properties.getDefaultBaseUrl();
    }

    /**
     * 获取指定 Provider 的 API Key
     *
     * @param provider 厂商/通道标识
     * @return 原始 API Key
     */
    public String getApiKey(String provider) {
        if ("default".equalsIgnoreCase(provider) || "local".equalsIgnoreCase(provider) || provider == null) {
            String envKey = System.getenv("OPENAI_API_KEY");
            if (envKey != null && !envKey.isBlank()) {
                return envKey;
            }
            return properties.getDefaultApiKey();
        }

        GatewayCredentialProperties.ProviderCredential cred = properties.getProviders().get(provider);
        if (cred != null && cred.getApiKey() != null && !cred.getApiKey().isBlank()) {
            return cred.getApiKey();
        }
        return properties.getDefaultApiKey();
    }

    /**
     * 获取脱敏后的 API Key 用于日志和安全打印
     *
     * @param provider 厂商/通道标识
     * @return 掩码后的字符串 (如 sk-proj-****1234)
     */
    public String getMaskedApiKey(String provider) {
        return mask(getApiKey(provider));
    }

    /**
     * 获取缺省默认模型名称
     *
     * @return 默认模型名称
     */
    public String getDefaultModel() {
        String envModel = System.getenv("MODEL_NAME");
        if (envModel != null && !envModel.isBlank()) {
            return envModel;
        }
        return properties.getDefaultModel();
    }

    /**
     * 对任意敏感密钥字符串执行安全脱敏
     *
     * @param apiKey 原始 Key
     * @return 掩码后的安全字符串
     */
    public static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "****";
        }
        int len = apiKey.length();
        if (len <= 8) {
            return "****";
        }
        int prefixLen = Math.min(7, len - 4);
        return apiKey.substring(0, prefixLen) + "****" + apiKey.substring(len - 4);
    }
}
