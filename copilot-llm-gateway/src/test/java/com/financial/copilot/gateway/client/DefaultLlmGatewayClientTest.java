package com.financial.copilot.gateway.client;

import com.financial.copilot.gateway.credential.GatewayCredentialManager;
import com.financial.copilot.gateway.credential.GatewayCredentialProperties;
import com.financial.copilot.gateway.governance.GatewayAuditSink;
import com.financial.copilot.gateway.governance.GatewayCapacityLimiter;
import com.financial.copilot.gateway.model.LlmAuditRecord;
import com.financial.copilot.gateway.model.LlmGatewayRequest;
import com.financial.copilot.gateway.model.LlmGatewayResponse;
import com.financial.copilot.gateway.model.LlmHealthReport;
import com.financial.copilot.gateway.model.LlmRoutingScene;
import com.financial.copilot.gateway.router.LlmFailoverExecutor;
import com.financial.copilot.gateway.router.LlmRouter;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>DefaultLlmGatewayClient 单元测试</h1>
 */
class DefaultLlmGatewayClientTest {

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
        auditSink = new GatewayAuditSink(50);
    }

    @Test
    @DisplayName("正常请求执行时，获取并释放容量许可，返回响应且记录审计日志")
    void shouldExecuteSuccessfullyAndRecordAudit() {
        List<LlmAuditRecord> recorded = new ArrayList<>();
        auditSink.addAuditListener(recorded::add);

        LlmFailoverExecutor executor = new LlmFailoverExecutor(capacityLimiter, auditSink, (req, spec) -> Mono.just(
                LlmGatewayResponse.builder()
                        .content("Normal response")
                        .actualModel(spec.modelName())
                        .actualProvider(spec.provider())
                        .promptTokens(10)
                        .completionTokens(20)
                        .totalTokens(30)
                        .isFailover(false)
                        .build()
        ));

        DefaultLlmGatewayClient client = new DefaultLlmGatewayClient(
                credentialManager,
                router,
                executor,
                capacityLimiter,
                auditSink
        );

        LlmGatewayRequest req = LlmGatewayRequest.builder()
                .scene(LlmRoutingScene.STANDARD)
                .userPrompt("Hello financial copilot")
                .build();

        StepVerifier.create(client.execute(req))
                .assertNext(resp -> {
                    assertThat(resp.content()).isEqualTo("Normal response");
                    assertThat(resp.actualProvider()).isEqualTo("codex-bridge");
                    assertThat(resp.isFailover()).isFalse();
                })
                .verifyComplete();

        // 验证容量释放
        assertThat(capacityLimiter.availablePermits()).isEqualTo(4);

        // 验证审计日志
        assertThat(recorded).hasSize(1);
        LlmAuditRecord audit = recorded.get(0);
        assertThat(audit.errorMessage()).isNull();
        assertThat(audit.isFailover()).isFalse();
        assertThat(audit.actualProvider()).isEqualTo("codex-bridge");
        assertThat(audit.totalTokens()).isEqualTo(30);
    }

    @Test
    @DisplayName("主链路发生异常时，故障自愈执行器平滑切换至本地 Codex-Bridge 且标记 isFailover")
    void shouldFailoverWhenPrimaryFails() {
        List<LlmAuditRecord> recorded = new ArrayList<>();
        auditSink.addAuditListener(recorded::add);

        LlmFailoverExecutor executor = new LlmFailoverExecutor(capacityLimiter, auditSink, (req, spec) -> {
            if ("codex-bridge".equals(spec.provider())) {
                return Mono.error(new RuntimeException("503 Service Unavailable"));
            }
            return Mono.just(LlmGatewayResponse.builder()
                    .content("Codex bridge fallback response")
                    .actualModel(spec.modelName())
                    .actualProvider(spec.provider())
                    .promptTokens(5)
                    .completionTokens(15)
                    .totalTokens(20)
                    .isFailover(false)
                    .build());
        });

        DefaultLlmGatewayClient client = new DefaultLlmGatewayClient(
                credentialManager,
                router,
                executor,
                capacityLimiter,
                auditSink
        );

        LlmGatewayRequest req = LlmGatewayRequest.builder()
                .scene(LlmRoutingScene.STANDARD)
                .userPrompt("Analyze asset")
                .build();

        StepVerifier.create(client.execute(req))
                .assertNext(resp -> {
                    assertThat(resp.content()).isEqualTo("Codex bridge fallback response");
                    assertThat(resp.actualProvider()).isEqualTo("codex-bridge-fallback");
                    assertThat(resp.isFailover()).isTrue();
                })
                .verifyComplete();

        assertThat(capacityLimiter.availablePermits()).isEqualTo(4);
        assertThat(recorded).hasSize(2); // 1 error audit for primary + 1 success audit for fallback
        LlmAuditRecord finalAudit = recorded.get(1);
        assertThat(finalAudit.errorMessage()).isNull();
        assertThat(finalAudit.isFailover()).isTrue();
        assertThat(finalAudit.actualProvider()).isEqualTo("codex-bridge-fallback");
    }

    @Test
    @DisplayName("创建 AgentScope Model 适配器成功")
    void shouldCreateAgentScopeModel() {
        LlmFailoverExecutor executor = new LlmFailoverExecutor(capacityLimiter, auditSink);
        DefaultLlmGatewayClient client = new DefaultLlmGatewayClient(
                credentialManager,
                router,
                executor,
                capacityLimiter,
                auditSink
        );

        Model model = client.createAgentScopeModel(LlmRoutingScene.FAST, "test-preferred-model");
        assertThat(model).isNotNull();
        assertThat(model.getModelName()).isEqualTo("test-preferred-model");
    }

    @Test
    @DisplayName("健康诊断接口输出正确的健康就绪状态")
    void shouldReportHealthStatusAccurately() {
        LlmFailoverExecutor executor = new LlmFailoverExecutor(capacityLimiter, auditSink);
        DefaultLlmGatewayClient client = new DefaultLlmGatewayClient(
                credentialManager,
                router,
                executor,
                capacityLimiter,
                auditSink
        );

        LlmHealthReport health = client.checkHealth();
        assertThat(health.healthy()).isTrue();
        assertThat(health.details()).isNotNull();
        assertThat(health.details().get("codex-bridge")).isTrue();
    }
}
