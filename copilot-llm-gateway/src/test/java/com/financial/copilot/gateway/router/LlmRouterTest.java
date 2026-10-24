package com.financial.copilot.gateway.router;

import com.financial.copilot.gateway.credential.GatewayCredentialManager;
import com.financial.copilot.gateway.credential.GatewayCredentialProperties;
import com.financial.copilot.gateway.model.LlmRoutingScene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>LlmRouterTest</h1>
 * <p>多场景路由链解析测试。</p>
 */
@DisplayName("LlmRouter 智能场景路由测试")
class LlmRouterTest {

    private LlmRouter router;

    @BeforeEach
    void setUp() {
        GatewayCredentialProperties properties = new GatewayCredentialProperties();
        GatewayCredentialManager credentialManager = new GatewayCredentialManager(properties);
        router = new LlmRouter(credentialManager);
    }

    @Test
    @DisplayName("FAST 场景默认路由至本地 codex-bridge flash-low 且具备回退链路")
    void shouldRouteFastSceneToLocalCodexBridge() {
        List<ProviderEndpointSpec> route = router.resolveRoute(LlmRoutingScene.FAST, null);

        assertThat(route).isNotEmpty();
        ProviderEndpointSpec primary = route.get(0);
        assertThat(primary.baseUrl()).isEqualTo("http://127.0.0.1:9090/v1");
        assertThat(primary.modelName()).isEqualTo("gemini-3.8-flash-low");
        assertThat(primary.isLocal()).isTrue();
    }

    @Test
    @DisplayName("REASONING 场景首选推理模型并以本地 Flash-High 兜底")
    void shouldRouteReasoningSceneWithFallback() {
        List<ProviderEndpointSpec> route = router.resolveRoute(LlmRoutingScene.REASONING, null);

        assertThat(route).hasSizeGreaterThanOrEqualTo(2);
        assertThat(route.get(0).modelName()).containsAnyOf("reasoner", "high", "r1");
    }

    @Test
    @DisplayName("用户指定 preferredModel 时优先尊重用户偏好")
    void shouldHonorPreferredModel() {
        List<ProviderEndpointSpec> route = router.resolveRoute(LlmRoutingScene.STANDARD, "claude-sonnet-4-6");

        assertThat(route).isNotEmpty();
        assertThat(route.get(0).modelName()).isEqualTo("claude-sonnet-4-6");
    }
}
