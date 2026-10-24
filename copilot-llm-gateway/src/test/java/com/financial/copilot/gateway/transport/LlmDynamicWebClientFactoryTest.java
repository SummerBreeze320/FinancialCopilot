package com.financial.copilot.gateway.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>LlmDynamicWebClientFactory 单元测试</h1>
 */
class LlmDynamicWebClientFactoryTest {

    @Test
    @DisplayName("相同 baseUrl 与 apiKey 时复用同一个 WebClient 实例")
    void shouldReuseWebClientInstanceForSameCredentials() {
        LlmDynamicWebClientFactory factory = new LlmDynamicWebClientFactory();
        WebClient client1 = factory.getOrCreateWebClient("http://127.0.0.1:9090/v1", "local-agy");
        WebClient client2 = factory.getOrCreateWebClient("http://127.0.0.1:9090/v1", "local-agy");

        assertThat(client1).isNotNull();
        assertThat(client2).isSameAs(client1);
    }

    @Test
    @DisplayName("不同 baseUrl 或 apiKey 时创建不同实例")
    void shouldCreateDistinctClientsForDifferentCredentials() {
        LlmDynamicWebClientFactory factory = new LlmDynamicWebClientFactory();
        WebClient client1 = factory.getOrCreateWebClient("http://127.0.0.1:9090/v1", "key1");
        WebClient client2 = factory.getOrCreateWebClient("http://127.0.0.1:9090/v1", "key2");

        assertThat(client1).isNotSameAs(client2);
    }
}
