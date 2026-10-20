package com.financial.copilot.gateway.credential;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * <h1>网关凭据与 Provider 配置属性 (GatewayCredentialProperties)</h1>
 * <p>绑定 copilot.gateway 命名空间下的多模型厂商端点配置与密钥。</p>
 *
 * @author FinancialCopilot
 */
@Data
@Component
@ConfigurationProperties(prefix = "copilot.gateway")
public class GatewayCredentialProperties {

    /**
     * 默认缺省端点地址
     */
    private String defaultBaseUrl = "http://127.0.0.1:9090/v1";

    /**
     * 默认缺省密钥
     */
    private String defaultApiKey = "local-agy";

    /**
     * 默认模型名称
     */
    private String defaultModel = "gemini-3.8-flash-low";

    /**
     * 各厂商或通道的专属凭据配置字典
     */
    private Map<String, ProviderCredential> providers = new HashMap<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProviderCredential {
        private String baseUrl;
        private String apiKey;
        private String defaultModel;
    }
}
