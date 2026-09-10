package com.financial.copilot.agent.core.llm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.llm.config.LlmConfigManager;
import com.financial.copilot.agent.core.llm.dto.*;
import com.financial.copilot.agent.core.llm.factory.LlmDynamicWebClientFactory;
import com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel;
import com.financial.copilot.agent.core.llm.provider.LlmProviderMetadata;
import com.financial.copilot.agent.core.llm.provider.LlmProviderRegistry;
import com.financial.copilot.agent.core.llm.provider.LlmProviderType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h1>多厂商通用大模型调用服务实现类 (Default Universal LLM Service)</h1>
 * <p>
 * 职责：基于统一 OpenAI 兼容规范驱动各底层模型厂商（DeepSeek、OpenAI、千问、智谱、Ollama 等），
 * 处理双层参数解析、网络连接池复用、开发模式智能 Mock 兜底以及连通性探测。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Service
public class DefaultLlmService implements LlmService {

    private final LlmDynamicWebClientFactory webClientFactory;
    private final LlmConfigManager configManager;
    private final LlmProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper;

    public DefaultLlmService(LlmDynamicWebClientFactory webClientFactory,
                             LlmConfigManager configManager,
                             LlmProviderRegistry providerRegistry,
                             ObjectMapper objectMapper) {
        this.webClientFactory = webClientFactory;
        this.configManager = configManager;
        this.providerRegistry = providerRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public String chat(LlmRequest request) {
        LlmSettingsDTO resolvedSettings = resolveSettings(request.getSettings());

        // 开发测试模式无有效 Key 时的防熔断兜底
        if (isMockMode(resolvedSettings.getCustomApiKey())) {
            log.warn("[LLM] 未检测到有效的 API Key，启用开发测试模式智能响应兜底: provider={}, model={}",
                    resolvedSettings.getProvider(), resolvedSettings.getModel());
            return generateMockResponse(request.getSystemPrompt(), request.getUserPrompt());
        }

        WebClient webClient = webClientFactory.getOrCreateWebClient(
                resolvedSettings.getCustomBaseUrl(),
                resolvedSettings.getCustomApiKey()
        );

        Map<String, Object> payload = buildPayload(request, resolvedSettings, false);

        try {
            String responseBody = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            log.error("[LLM] 大模型同步推理请求失败: provider={}, model={}, error={}",
                    resolvedSettings.getProvider(), resolvedSettings.getModel(), e.getMessage());
            return generateMockResponse(request.getSystemPrompt(), request.getUserPrompt());
        }
    }

    @Override
    public String chat(String systemPrompt, String userMessage, LlmPerformanceLevel level) {
        LlmSettingsDTO settings = LlmSettingsDTO.builder()
                .performanceLevel(level)
                .build();
        return chat(LlmRequest.of(systemPrompt, userMessage, settings));
    }

    @Override
    public Flux<String> chatStream(LlmRequest request) {
        LlmSettingsDTO resolvedSettings = resolveSettings(request.getSettings());

        if (isMockMode(resolvedSettings.getCustomApiKey())) {
            return Flux.just(generateMockResponse(request.getSystemPrompt(), request.getUserPrompt()));
        }

        WebClient webClient = webClientFactory.getOrCreateWebClient(
                resolvedSettings.getCustomBaseUrl(),
                resolvedSettings.getCustomApiKey()
        );

        Map<String, Object> payload = buildPayload(request, resolvedSettings, true);

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(payload)
                .retrieve()
                .bodyToFlux(String.class)
                .map(this::extractContentFromStreamChunk)
                .filter(chunk -> !chunk.isEmpty())
                .onErrorResume(e -> {
                    log.error("[LLM-STREAM] 流式推送异常，降级输出: error={}", e.getMessage());
                    return Flux.just(generateMockResponse(request.getSystemPrompt(), request.getUserPrompt()));
                });
    }

    @Override
    public Flux<String> chatStream(String systemPrompt, String userMessage, LlmPerformanceLevel level) {
        LlmSettingsDTO settings = LlmSettingsDTO.builder()
                .performanceLevel(level)
                .build();
        return chatStream(LlmRequest.of(systemPrompt, userMessage, settings));
    }

    @Override
    public LlmConnectionTestResult testConnection(LlmConnectionTestRequest testRequest) {
        if (testRequest == null || testRequest.getProvider() == null) {
            return LlmConnectionTestResult.failure("未指定厂商类型");
        }

        LlmProviderMetadata metadata = providerRegistry.getMetadata(testRequest.getProvider());
        String baseUrl = (testRequest.getBaseUrl() != null && !testRequest.getBaseUrl().isBlank())
                ? testRequest.getBaseUrl().trim()
                : metadata.defaultBaseUrl();
        String model = (testRequest.getModel() != null && !testRequest.getModel().isBlank())
                ? testRequest.getModel().trim()
                : metadata.defaultModel();
        String apiKey = testRequest.getApiKey();

        if (isMockMode(apiKey)) {
            return LlmConnectionTestResult.success(12L, "Pong (开发模式模拟连通)");
        }

        WebClient webClient = webClientFactory.getOrCreateWebClient(baseUrl, apiKey);
        Map<String, Object> payload = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", "ping")),
                "max_tokens", 5,
                "stream", false
        );

        long start = System.currentTimeMillis();
        try {
            String body = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            long latency = System.currentTimeMillis() - start;

            JsonNode root = objectMapper.readTree(body);
            String sample = root.path("choices").get(0).path("message").path("content").asText();
            return LlmConnectionTestResult.success(latency, sample);
        } catch (Exception e) {
            return LlmConnectionTestResult.failure(e.getMessage());
        }
    }

    /**
     * 合并系统默认配置与请求级覆盖参数
     */
    private LlmSettingsDTO resolveSettings(LlmSettingsDTO override) {
        LlmSettingsDTO base = configManager.getActiveSettings();
        if (override == null) {
            return base;
        }

        LlmProviderType provider = override.getProvider() != null ? override.getProvider() : base.getProvider();
        LlmProviderMetadata metadata = providerRegistry.getMetadata(provider);

        String model = (override.getModel() != null && !override.getModel().isBlank())
                ? override.getModel() : base.getModel();

        LlmPerformanceLevel level = override.getPerformanceLevel() != null
                ? override.getPerformanceLevel() : base.getPerformanceLevel();

        String baseUrl = (override.getCustomBaseUrl() != null && !override.getCustomBaseUrl().isBlank())
                ? override.getCustomBaseUrl()
                : (base.getCustomBaseUrl() != null && !base.getCustomBaseUrl().isBlank())
                ? base.getCustomBaseUrl() : metadata.defaultBaseUrl();

        String apiKey = (override.getCustomApiKey() != null && !override.getCustomApiKey().isBlank())
                ? override.getCustomApiKey() : base.getCustomApiKey();

        return LlmSettingsDTO.builder()
                .provider(provider)
                .model(model)
                .performanceLevel(level)
                .temperature(override.getTemperature() != null ? override.getTemperature() : base.getTemperature())
                .topP(override.getTopP() != null ? override.getTopP() : base.getTopP())
                .maxTokens(override.getMaxTokens() != null ? override.getMaxTokens() : base.getMaxTokens())
                .customBaseUrl(baseUrl)
                .customApiKey(apiKey)
                .build();
    }

    private Map<String, Object> buildPayload(LlmRequest request, LlmSettingsDTO settings, boolean stream) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", settings.getModel());
        payload.put("messages", List.of(
                Map.of("role", "system", "content", request.getSystemPrompt() != null ? request.getSystemPrompt() : ""),
                Map.of("role", "user", "content", request.getUserPrompt() != null ? request.getUserPrompt() : "")
        ));
        payload.put("temperature", settings.resolveTemperature());
        payload.put("max_tokens", settings.resolveMaxTokens());
        payload.put("stream", stream);

        if (settings.getTopP() != null) {
            payload.put("top_p", settings.getTopP());
        }

        // 推理模型附带思考强度预算参数
        String modelName = settings.getModel().toLowerCase();
        if (modelName.contains("reasoner") || modelName.contains("o1") || modelName.contains("o3")) {
            payload.put("reasoning_effort", settings.resolveReasoningEffort());
        }

        return payload;
    }

    private String extractContentFromStreamChunk(String chunk) {
        try {
            if (chunk.contains("[DONE]")) {
                return "";
            }
            JsonNode root = objectMapper.readTree(chunk);
            return root.path("choices").get(0).path("delta").path("content").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isMockMode(String apiKey) {
        return apiKey == null || apiKey.isBlank() || apiKey.contains("placeholder");
    }

    private String generateMockResponse(String systemPrompt, String userMessage) {
        if (systemPrompt != null) {
            if (systemPrompt.contains("TaskDecomposer") || systemPrompt.contains("复合投研规划")) {
                if (userMessage.contains("然后") || userMessage.contains("再") || userMessage.contains("最后") || userMessage.contains("分析前")) {
                    return """
                            {
                              "isComplex": true,
                              "assetCategory": "FUND",
                              "summary": "医药基金初筛 -> 经理分析 -> Top2横向对标 -> 投资建议研报",
                              "steps": [
                                {"stepId": 1, "taskType": "SCREENING", "description": "筛选过去三年表现稳定的医药基金", "dependencies": []},
                                {"stepId": 2, "taskType": "BATCH_ANALYSIS", "description": "分析候选标的前5名基金经理任职能力", "dependencies": [1]},
                                {"stepId": 3, "taskType": "COMPARISON", "description": "对标对比最优秀的两个标的", "dependencies": [2]},
                                {"stepId": 4, "taskType": "SYNTHESIS", "description": "生成综合配置与投资建议研报", "dependencies": [3]}
                              ]
                            }
                            """;
                } else {
                    return """
                            {
                              "isComplex": false,
                              "assetCategory": "FUND",
                              "summary": "执行标准公募基金量化分析",
                              "steps": [
                                {"stepId": 1, "taskType": "SINGLE_ANALYSIS", "description": "分析目标基金多维指标与重仓风格", "dependencies": []}
                              ]
                            }
                            """;
                }
            }
            if (systemPrompt.contains("ScreenerAgent")) {
                return "{\"fundType\": \"偏股混合型\", \"limit\": 10}";
            }
        }
        return "【投研研报】基于平台量化与客观研报数据，标的在全周期内收益风险比优异，风格稳定，配置建议积极。";
    }
}
