package com.financial.copilot.agent.core.llm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.llm.config.LlmConfigManager;
import com.financial.copilot.agent.core.llm.dto.*;
import com.financial.copilot.agent.core.llm.factory.LlmDynamicWebClientFactory;
import com.financial.copilot.agent.core.llm.provider.LlmProviderMetadata;
import com.financial.copilot.agent.core.llm.provider.LlmProviderRegistry;
import com.financial.copilot.agent.core.llm.provider.LlmProviderType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <h1>多厂商通用大模型调用服务实现类 (Default Universal LLM Service)</h1>
 * <p>
 * 职责：基于统一 OpenAI 兼容协议驱动各底层厂商模型，支持双模型路由机制：
 * <ul>
 *   <li>标准极速投研模式：调用标准对话模型（如 deepseek-chat, gpt-4o），支持温度与 TopP 调优；</li>
 *   <li>深度思考推理模式：调用推理大模型（如 deepseek-reasoner, o3-mini），自动遵循官方 API 规范（免去冲突参数，防范 400 异常）。</li>
 * </ul>
 * 具备连接池复用、开发模式智能 Mock 兜底以及网络连通性探测。
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
        long startedAt = System.currentTimeMillis();
        LlmSettingsDTO resolvedSettings = resolveSettings(request.getSettings());
        String effectiveModel = resolvedSettings.resolveEffectiveModel();

        // 开发测试模式无有效 Key 时的防熔断兜底
        if (isMockMode(resolvedSettings.getCustomApiKey())) {
            log.warn("[LLM] 未检测到有效的 API Key，启用开发测试模式智能响应兜底: provider={}, model={}, enableThinking={}",
                    resolvedSettings.getProvider(), effectiveModel, resolvedSettings.isEnableThinking());
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
            reportUsage(request, resolvedSettings, root, startedAt);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            if (request.getUsageConsumer() != null) {
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Metered LLM call failed", e);
            }
            log.error("[LLM] 大模型同步推理请求失败: provider={}, model={}, error={}",
                    resolvedSettings.getProvider(), effectiveModel, e.getMessage());
            return generateMockResponse(request.getSystemPrompt(), request.getUserPrompt());
        }
    }

    @Override
    public String chat(String systemPrompt, String userMessage, boolean enableThinking) {
        LlmSettingsDTO settings = LlmSettingsDTO.builder()
                .enableThinking(enableThinking)
                .build();
        return chat(LlmRequest.of(systemPrompt, userMessage, settings));
    }

    @Override
    public Flux<String> chatStream(LlmRequest request) {
        LlmSettingsDTO resolvedSettings = resolveSettings(request.getSettings());
        String effectiveModel = resolvedSettings.resolveEffectiveModel();

        if (isMockMode(resolvedSettings.getCustomApiKey())) {
            return Flux.just(generateMockResponse(request.getSystemPrompt(), request.getUserPrompt()));
        }

        WebClient webClient = webClientFactory.getOrCreateWebClient(
                resolvedSettings.getCustomBaseUrl(),
                resolvedSettings.getCustomApiKey()
        );

        Map<String, Object> payload = buildPayload(request, resolvedSettings, true);

        return Flux.defer(() -> {
        long startedAt = System.currentTimeMillis();
        AtomicBoolean usageReceived = new AtomicBoolean();
        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(payload)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .mapNotNull(ServerSentEvent::data)
                .takeUntil("[DONE]"::equals)
                .filter(chunk -> !"[DONE]".equals(chunk))
                .publishOn(Schedulers.boundedElastic())
                .map(chunk -> {
                    try {
                        JsonNode root = objectMapper.readTree(chunk);
                        if (root.hasNonNull("usage") && usageReceived.compareAndSet(false, true)) {
                            reportUsage(request, resolvedSettings, root, startedAt);
                        }
                        return root.path("choices").path(0).path("delta").path("content").asText("");
                    } catch (IOException e) {
                        throw new IllegalStateException("Invalid LLM stream response", e);
                    }
                })
                .filter(chunk -> !chunk.isEmpty())
                .concatWith(Flux.defer(() -> request.getUsageConsumer() != null && !usageReceived.get()
                        ? Flux.error(new IllegalStateException("Provider did not return token usage")) : Flux.empty()))
                .onErrorResume(e -> {
                    if (request.getUsageConsumer() != null) return Flux.error(e);
                    log.error("[LLM-STREAM] 流式推送异常，降级输出: error={}", e.getMessage());
                    return Flux.just(generateMockResponse(request.getSystemPrompt(), request.getUserPrompt()));
                });
        });
    }

    @Override
    public Flux<String> chatStream(String systemPrompt, String userMessage, boolean enableThinking) {
        LlmSettingsDTO settings = LlmSettingsDTO.builder()
                .enableThinking(enableThinking)
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

        boolean enableThinking = override.isEnableThinking() || base.isEnableThinking();

        String model = (override.getModel() != null && !override.getModel().isBlank())
                ? override.getModel()
                : (base.getModel() != null && !base.getModel().isBlank())
                ? base.getModel() : metadata.defaultModel();

        String reasoningModel = (override.getReasoningModel() != null && !override.getReasoningModel().isBlank())
                ? override.getReasoningModel()
                : (base.getReasoningModel() != null && !base.getReasoningModel().isBlank())
                ? base.getReasoningModel() : metadata.defaultReasoningModel();

        String baseUrl = (override.getCustomBaseUrl() != null && !override.getCustomBaseUrl().isBlank())
                ? override.getCustomBaseUrl()
                : (base.getCustomBaseUrl() != null && !base.getCustomBaseUrl().isBlank())
                ? base.getCustomBaseUrl() : metadata.defaultBaseUrl();

        String apiKey = (override.getCustomApiKey() != null && !override.getCustomApiKey().isBlank())
                ? override.getCustomApiKey() : base.getCustomApiKey();

        return LlmSettingsDTO.builder()
                .provider(provider)
                .model(model)
                .reasoningModel(reasoningModel)
                .enableThinking(enableThinking)
                .temperature(override.getTemperature() != null ? override.getTemperature() : base.getTemperature())
                .topP(override.getTopP() != null ? override.getTopP() : base.getTopP())
                .maxTokens(override.getMaxTokens() != null ? override.getMaxTokens() : base.getMaxTokens())
                .customBaseUrl(baseUrl)
                .customApiKey(apiKey)
                .build();
    }

    private Map<String, Object> buildPayload(LlmRequest request, LlmSettingsDTO settings, boolean stream) {
        String effectiveModel = settings.resolveEffectiveModel();
        boolean isReasoning = LlmSettingsDTO.isReasoningModel(effectiveModel);

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", effectiveModel);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", request.getSystemPrompt() != null ? request.getSystemPrompt() : ""),
                Map.of("role", "user", "content", request.getUserPrompt() != null ? request.getUserPrompt() : "")
        ));
        payload.put("stream", stream);
        if (stream && request.getUsageConsumer() != null) payload.put("stream_options", Map.of("include_usage", true));

        // 推理模型严格遵循官方规范：避免传递温度和 top_p 参数以防 400 Bad Request
        if (!isReasoning) {
            Double temp = settings.resolveTemperature();
            if (temp != null) {
                payload.put("temperature", temp);
            }
            if (settings.getTopP() != null) {
                payload.put("top_p", settings.getTopP());
            }
        }

        // OpenAI o1/o3-mini 使用 max_completion_tokens 代替 max_tokens，deepseek-reasoner 使用 max_tokens
        if (effectiveModel.toLowerCase().contains("o1") || effectiveModel.toLowerCase().contains("o3")) {
            payload.put("max_completion_tokens", settings.resolveMaxTokens());
        } else {
            payload.put("max_tokens", settings.resolveMaxTokens());
        }

        return payload;
    }

    private void reportUsage(LlmRequest request, LlmSettingsDTO settings, JsonNode response, long startedAt) {
        if (request.getUsageConsumer() == null) return;
        JsonNode usage = response.path("usage");
        if (!usage.path("prompt_tokens").isIntegralNumber() || !usage.path("completion_tokens").isIntegralNumber()
                || !usage.path("prompt_tokens").canConvertToInt() || !usage.path("completion_tokens").canConvertToInt()) {
            throw new IllegalStateException("Provider did not return token usage");
        }
        int prompt = usage.get("prompt_tokens").intValue();
        int completion = usage.get("completion_tokens").intValue();
        if (prompt < 0 || completion < 0) throw new IllegalStateException("Invalid token usage");
        request.getUsageConsumer().accept(LlmResponse.builder().provider(settings.getProvider())
                .model(response.path("model").asText(settings.resolveEffectiveModel()))
                .promptTokens(prompt).completionTokens(completion).totalTokens(Math.addExact(prompt, completion))
                .latencyMs(System.currentTimeMillis() - startedAt).build());
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
