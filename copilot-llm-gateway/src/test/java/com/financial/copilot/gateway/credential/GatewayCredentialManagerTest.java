package com.financial.copilot.gateway.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>GatewayCredentialManagerTest</h1>
 * <p>测试凭据管理、默认 codex-bridge 回退与 Key 安全掩码输出。</p>
 */
@DisplayName("GatewayCredentialManager 凭据与安全脱敏测试")
class GatewayCredentialManagerTest {

    @Test
    @DisplayName("缺省无配置时，默认优先回退至本地 codex-bridge 参数")
    void shouldFallbackToLocalCodexBridgeDefaults() {
        GatewayCredentialProperties properties = new GatewayCredentialProperties();
        GatewayCredentialManager manager = new GatewayCredentialManager(properties);

        assertThat(manager.getBaseUrl("default")).isEqualTo("http://127.0.0.1:9090/v1");
        assertThat(manager.getApiKey("default")).isEqualTo("local-agy");
        assertThat(manager.getDefaultModel()).isEqualTo("gemini-3.8-flash-low");
    }

    @Test
    @DisplayName("配置自定义 Provider 时正确读取其参数")
    void shouldUseCustomProviderCredentials() {
        GatewayCredentialProperties properties = new GatewayCredentialProperties();
        properties.getProviders().put("deepseek", GatewayCredentialProperties.ProviderCredential.builder()
                .baseUrl("https://api.deepseek.com/v1")
                .apiKey("sk-proj-1234567890abcdef")
                .defaultModel("deepseek-chat")
                .build());

        GatewayCredentialManager manager = new GatewayCredentialManager(properties);

        assertThat(manager.getBaseUrl("deepseek")).isEqualTo("https://api.deepseek.com/v1");
        assertThat(manager.getApiKey("deepseek")).isEqualTo("sk-proj-1234567890abcdef");
    }

    @Test
    @DisplayName("API Key 安全脱敏掩码测试")
    void shouldMaskApiKeyCorrectly() {
        GatewayCredentialProperties properties = new GatewayCredentialProperties();
        properties.getProviders().put("online", GatewayCredentialProperties.ProviderCredential.builder()
                .apiKey("sk-live-1234567890abcdef1234")
                .build());

        GatewayCredentialManager manager = new GatewayCredentialManager(properties);

        String masked = manager.getMaskedApiKey("online");
        assertThat(masked).startsWith("sk-live");
        assertThat(masked).endsWith("1234");
        assertThat(masked).contains("****");
        assertThat(masked).doesNotContain("1234567890abcdef");

        // 短 key 测试
        String shortMasked = GatewayCredentialManager.mask("local");
        assertThat(shortMasked).isEqualTo("****");
    }
}
