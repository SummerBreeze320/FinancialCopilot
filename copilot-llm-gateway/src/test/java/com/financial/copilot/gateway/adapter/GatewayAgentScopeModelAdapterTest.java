package com.financial.copilot.gateway.adapter;

import com.financial.copilot.gateway.credential.GatewayCredentialManager;
import com.financial.copilot.gateway.credential.GatewayCredentialProperties;
import com.financial.copilot.gateway.governance.GatewayAuditSink;
import com.financial.copilot.gateway.governance.GatewayCapacityLimiter;
import com.financial.copilot.gateway.model.LlmAuditRecord;
import com.financial.copilot.gateway.model.LlmRoutingScene;
import com.financial.copilot.gateway.router.LlmRouter;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <h1>GatewayAgentScopeModelAdapter 单元测试</h1>
 */
class GatewayAgentScopeModelAdapterTest {

    private GatewayCredentialManager credentialManager;
    private LlmRouter router;
    private GatewayCapacityLimiter capacityLimiter;
    private GatewayAuditSink auditSink;

    @BeforeEach
    void setUp() {
        GatewayCredentialProperties properties = new GatewayCredentialProperties();
        credentialManager = new GatewayCredentialManager(properties);
        router = new LlmRouter(credentialManager);
        capacityLimiter = new GatewayCapacityLimiter(4);
        auditSink = new GatewayAuditSink(100);
    }

    @Test
    @DisplayName("首选模型正常流式推理时，成功输出响应并生成正常审计记录")
    void shouldStreamNormallyWhenPrimarySucceeds() {
        List<LlmAuditRecord> recorded = new ArrayList<>();
        auditSink.addAuditListener(recorded::add);

        ChatResponse mockResp = ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("Hello agent").build()))
                .usage(new ChatUsage(15, 25, 40, 0.3))
                .build();

        Model mockDelegate = mock(Model.class);
        when(mockDelegate.stream(any(), any(), any())).thenReturn(Flux.just(mockResp));

        GatewayAgentScopeModelAdapter adapter = new GatewayAgentScopeModelAdapter(
                LlmRoutingScene.STANDARD,
                null,
                router,
                capacityLimiter,
                auditSink,
                spec -> mockDelegate
        );

        StepVerifier.create(adapter.stream(Collections.emptyList(), Collections.emptyList(), null))
                .assertNext(resp -> {
                    assertThat(resp.getContent()).isNotEmpty();
                    TextBlock tb = (TextBlock) resp.getContent().get(0);
                    assertThat(tb.getText()).isEqualTo("Hello agent");
                })
                .verifyComplete();

        assertThat(recorded).hasSize(1);
        LlmAuditRecord audit = recorded.get(0);
        assertThat(audit.errorMessage()).isNull();
        assertThat(audit.isFailover()).isFalse();
        assertThat(audit.totalTokens()).isEqualTo(40);
        assertThat(audit.promptTokens()).isEqualTo(15);
        assertThat(audit.completionTokens()).isEqualTo(25);
    }

    @Test
    @DisplayName("首选模型调用异常时，自动平滑转移至备选 Codex-Bridge 链路")
    void shouldFailoverWhenPrimaryErrors() {
        List<LlmAuditRecord> recorded = new ArrayList<>();
        auditSink.addAuditListener(recorded::add);

        ChatResponse failoverResp = ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("Fallback response from codex-bridge").build()))
                .usage(new ChatUsage(8, 12, 20, 0.1))
                .build();

        Model primaryModel = mock(Model.class);
        when(primaryModel.stream(any(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("503 Service Unavailable")));

        Model fallbackModel = mock(Model.class);
        when(fallbackModel.stream(any(), any(), any())).thenReturn(Flux.just(failoverResp));

        GatewayAgentScopeModelAdapter adapter = new GatewayAgentScopeModelAdapter(
                LlmRoutingScene.STANDARD,
                null,
                router,
                capacityLimiter,
                auditSink,
                spec -> "codex-bridge".equals(spec.provider()) ? primaryModel : fallbackModel
        );

        StepVerifier.create(adapter.stream(Collections.emptyList(), Collections.emptyList(), null))
                .assertNext(resp -> {
                    TextBlock tb = (TextBlock) resp.getContent().get(0);
                    assertThat(tb.getText()).isEqualTo("Fallback response from codex-bridge");
                })
                .verifyComplete();

        assertThat(recorded).hasSize(1);
        LlmAuditRecord audit = recorded.get(0);
        assertThat(audit.errorMessage()).isNull();
        assertThat(audit.isFailover()).isTrue();
        assertThat(audit.actualProvider()).isEqualTo("codex-bridge-fallback");
        assertThat(audit.actualModel()).isEqualTo("gemini-3.8-flash-low");
        assertThat(audit.totalTokens()).isEqualTo(20);
    }

    @Test
    @DisplayName("所有链路均失败时，记录失败审计并向下游抛出异常")
    void shouldThrowWhenAllRoutesFail() {
        List<LlmAuditRecord> recorded = new ArrayList<>();
        auditSink.addAuditListener(recorded::add);

        Model failingModel = mock(Model.class);
        when(failingModel.stream(any(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("Network timeout")));

        GatewayAgentScopeModelAdapter adapter = new GatewayAgentScopeModelAdapter(
                LlmRoutingScene.STANDARD,
                null,
                router,
                capacityLimiter,
                auditSink,
                spec -> failingModel
        );

        StepVerifier.create(adapter.stream(Collections.emptyList(), Collections.emptyList(), null))
                .expectError(IllegalStateException.class)
                .verify();

        assertThat(recorded).hasSize(1);
        LlmAuditRecord audit = recorded.get(0);
        assertThat(audit.errorMessage()).isNotNull();
        assertThat(audit.errorMessage()).contains("所有候选链路均执行失败");
    }
}
