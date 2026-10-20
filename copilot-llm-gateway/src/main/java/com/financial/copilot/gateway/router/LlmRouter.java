package com.financial.copilot.gateway.router;

import com.financial.copilot.gateway.credential.GatewayCredentialManager;
import com.financial.copilot.gateway.model.LlmRoutingScene;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * <h1>多场景智能路由分发器 (LlmRouter)</h1>
 * <p>
 * 依据 {@link LlmRoutingScene} 业务场景与用户偏好，动态解析首选与备用端点链路，
 * 默认且优先选择本地独立运行的 Codex-Bridge (http://127.0.0.1:9090/v1)。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class LlmRouter {

    private final GatewayCredentialManager credentialManager;

    @Autowired
    public LlmRouter(GatewayCredentialManager credentialManager) {
        this.credentialManager = Objects.requireNonNull(credentialManager, "credentialManager must not be null");
    }

    /**
     * 根据场景与偏好解析端点调用链路 (Primary -> Secondary Fallback)
     *
     * @param scene 业务场景
     * @param preferredModel 用户/上游指定的偏好模型
     * @return 有序的端点配置列表
     */
    public List<ProviderEndpointSpec> resolveRoute(LlmRoutingScene scene, String preferredModel) {
        LlmRoutingScene targetScene = scene != null ? scene : LlmRoutingScene.STANDARD;
        List<ProviderEndpointSpec> routeChain = new ArrayList<>();

        String defaultBaseUrl = credentialManager.getBaseUrl("default");
        String defaultApiKey = credentialManager.getApiKey("default");

        // 1. 若用户指定了偏好模型，优先排在首位
        if (preferredModel != null && !preferredModel.isBlank()) {
            routeChain.add(ProviderEndpointSpec.builder()
                    .provider("preferred")
                    .modelName(preferredModel)
                    .baseUrl(defaultBaseUrl)
                    .apiKey(defaultApiKey)
                    .timeout(Duration.ofSeconds(30))
                    .isLocal(defaultBaseUrl.contains("127.0.0.1") || defaultBaseUrl.contains("localhost"))
                    .build());
            // 兜底节点
            routeChain.add(localFallbackSpec());
            return List.copyOf(routeChain);
        }

        // 2. 依据场景解析
        switch (targetScene) {
            case FAST -> {
                routeChain.add(ProviderEndpointSpec.builder()
                        .provider("codex-bridge")
                        .modelName("gemini-3.8-flash-low")
                        .baseUrl(defaultBaseUrl)
                        .apiKey(defaultApiKey)
                        .timeout(Duration.ofSeconds(10))
                        .isLocal(true)
                        .build());
                routeChain.add(localFallbackSpec());
            }
            case REASONING -> {
                String onlineReasoningUrl = credentialManager.getBaseUrl("reasoning");
                String onlineReasoningKey = credentialManager.getApiKey("reasoning");
                routeChain.add(ProviderEndpointSpec.builder()
                        .provider("reasoning")
                        .modelName("deepseek-reasoner")
                        .baseUrl(onlineReasoningUrl)
                        .apiKey(onlineReasoningKey)
                        .timeout(Duration.ofSeconds(60))
                        .isLocal(onlineReasoningUrl.contains("127.0.0.1"))
                        .build());
                routeChain.add(ProviderEndpointSpec.builder()
                        .provider("codex-bridge-high")
                        .modelName("gemini-3.8-flash-high")
                        .baseUrl(defaultBaseUrl)
                        .apiKey(defaultApiKey)
                        .timeout(Duration.ofSeconds(30))
                        .isLocal(true)
                        .build());
                routeChain.add(localFallbackSpec());
            }
            case QUANT -> {
                routeChain.add(ProviderEndpointSpec.builder()
                        .provider("quant")
                        .modelName("gemini-3.8-flash-high")
                        .baseUrl(defaultBaseUrl)
                        .apiKey(defaultApiKey)
                        .timeout(Duration.ofSeconds(30))
                        .isLocal(true)
                        .build());
                routeChain.add(localFallbackSpec());
            }
            case FALLBACK -> {
                routeChain.add(localFallbackSpec());
            }
            case STANDARD -> {
                routeChain.add(ProviderEndpointSpec.builder()
                        .provider("codex-bridge")
                        .modelName("gemini-3.8-flash-low")
                        .baseUrl(defaultBaseUrl)
                        .apiKey(defaultApiKey)
                        .timeout(Duration.ofSeconds(30))
                        .isLocal(true)
                        .build());
                routeChain.add(localFallbackSpec());
            }
        }

        return List.copyOf(routeChain);
    }

    private ProviderEndpointSpec localFallbackSpec() {
        return ProviderEndpointSpec.builder()
                .provider("codex-bridge-fallback")
                .modelName("gemini-3.8-flash-low")
                .baseUrl(credentialManager.getBaseUrl("default"))
                .apiKey(credentialManager.getApiKey("default"))
                .timeout(Duration.ofSeconds(20))
                .isLocal(true)
                .build();
    }
}
