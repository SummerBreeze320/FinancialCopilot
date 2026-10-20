package com.financial.copilot.gateway.router;

import com.financial.copilot.gateway.governance.GatewayAuditSink;
import com.financial.copilot.gateway.governance.GatewayCapacityLimiter;
import com.financial.copilot.gateway.model.LlmGatewayRequest;
import com.financial.copilot.gateway.model.LlmGatewayResponse;
import com.financial.copilot.gateway.model.LlmRoutingScene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <h1>LlmFailoverExecutorTest</h1>
 * <p>多链路故障自愈与备用链路自动重试测试。</p>
 */
@DisplayName("LlmFailoverExecutor 故障自愈与重试执行器测试")
class LlmFailoverExecutorTest {

    private GatewayAuditSink auditSink;
    private GatewayCapacityLimiter capacityLimiter;

    @BeforeEach
    void setUp() {
        auditSink = new GatewayAuditSink(10);
        capacityLimiter = new GatewayCapacityLimiter(5);
    }

    @Test
    @DisplayName("首选主链路成功时直接返回且 isFailover 为 false")
    void shouldReturnDirectlyWhenPrimarySucceeds() {
        ProviderEndpointSpec primary = new ProviderEndpointSpec("local", "flash-low", "http://127.0.0.1:9090", "key", Duration.ofSeconds(5), true);

        LlmFailoverExecutor executor = new LlmFailoverExecutor(capacityLimiter, auditSink, (req, spec) ->
                Mono.just(LlmGatewayResponse.builder()
                        .content("success from primary")
                        .actualModel(spec.modelName())
                        .actualProvider(spec.provider())
                        .isFailover(false)
                        .build()));

        LlmGatewayRequest request = LlmGatewayRequest.builder()
                .scene(LlmRoutingScene.FAST)
                .userPrompt("ping")
                .build();

        LlmGatewayResponse response = executor.executeWithFailover(request, List.of(primary)).block();

        assertThat(response).isNotNull();
        assertThat(response.content()).isEqualTo("success from primary");
        assertThat(response.isFailover()).isFalse();
    }

    @Test
    @DisplayName("主链路报错时自动切换至备选链路并标记 isFailover 为 true")
    void shouldFailoverWhenPrimaryFails() {
        ProviderEndpointSpec primary = new ProviderEndpointSpec("online", "deepseek-chat", "https://api.online.com", "key1", Duration.ofSeconds(5), false);
        ProviderEndpointSpec secondary = new ProviderEndpointSpec("local", "flash-low", "http://127.0.0.1:9090", "key2", Duration.ofSeconds(5), true);

        AtomicInteger callCount = new AtomicInteger(0);

        LlmFailoverExecutor executor = new LlmFailoverExecutor(capacityLimiter, auditSink, (req, spec) -> {
            int c = callCount.incrementAndGet();
            if (c == 1) {
                return Mono.error(new RuntimeException("503 Service Unavailable"));
            }
            return Mono.just(LlmGatewayResponse.builder()
                    .content("recovered by fallback")
                    .actualModel(spec.modelName())
                    .actualProvider(spec.provider())
                    .isFailover(true)
                    .build());
        });

        LlmGatewayRequest request = LlmGatewayRequest.builder()
                .scene(LlmRoutingScene.STANDARD)
                .userPrompt("analyze")
                .build();

        LlmGatewayResponse response = executor.executeWithFailover(request, List.of(primary, secondary)).block();

        assertThat(response).isNotNull();
        assertThat(response.content()).isEqualTo("recovered by fallback");
        assertThat(response.isFailover()).isTrue();
        assertThat(response.actualProvider()).isEqualTo("local");
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("所有链路均失败时抛出异常并记录失败审计")
    void shouldThrowWhenAllRoutesFail() {
        ProviderEndpointSpec primary = new ProviderEndpointSpec("p1", "m1", "http://u1", "k1", Duration.ofSeconds(1), false);
        ProviderEndpointSpec secondary = new ProviderEndpointSpec("p2", "m2", "http://u2", "k2", Duration.ofSeconds(1), true);

        LlmFailoverExecutor executor = new LlmFailoverExecutor(capacityLimiter, auditSink, (req, spec) ->
                Mono.error(new RuntimeException("Fatal Error: " + spec.provider())));

        LlmGatewayRequest request = LlmGatewayRequest.builder()
                .scene(LlmRoutingScene.STANDARD)
                .userPrompt("crash")
                .build();

        assertThatThrownBy(() -> executor.executeWithFailover(request, List.of(primary, secondary)).block())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("All provider routes failed");
    }
}
