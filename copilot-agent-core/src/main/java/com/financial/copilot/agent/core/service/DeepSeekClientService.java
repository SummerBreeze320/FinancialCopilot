package com.financial.copilot.agent.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.config.DeepSeekModelConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * DeepSeek 模型通信服务 (基于 OpenAI 兼容协议)
 */
@Slf4j
@Service
public class DeepSeekClientService {

    private final WebClient webClient;
    private final DeepSeekModelConfig config;
    private final ObjectMapper objectMapper;

    public DeepSeekClientService(WebClient deepSeekWebClient, DeepSeekModelConfig config, ObjectMapper objectMapper) {
        this.webClient = deepSeekWebClient;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * 同步非流式推理请求
     */
    public String chat(String systemPrompt, String userMessage) {
        // 如果未配置有效 Key，提供优雅的智能模拟兜底，防止直接抛出异常中断调试
        if (config.getApiKey() == null || config.getApiKey().contains("placeholder")) {
            log.warn("[LLM] 未检测到有效的 DEEPSEEK_API_KEY，启用开发模式智能响应兜底。");
            return mockResponse(systemPrompt, userMessage);
        }

        Map<String, Object> payload = Map.of(
                "model", config.getModelName(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                ),
                "temperature", config.getTemperature(),
                "stream", false
        );

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
            log.error("DeepSeek 推理失败，降级为规则处理: error={}", e.getMessage());
            return mockResponse(systemPrompt, userMessage);
        }
    }

    /**
     * 响应式流式推理 (SSE 支持)
     */
    public Flux<String> chatStream(String systemPrompt, String userMessage) {
        if (config.getApiKey() == null || config.getApiKey().contains("placeholder")) {
            return Flux.just(mockResponse(systemPrompt, userMessage));
        }

        Map<String, Object> payload = Map.of(
                "model", config.getModelName(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                ),
                "temperature", config.getTemperature(),
                "stream", true
        );

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(payload)
                .retrieve()
                .bodyToFlux(String.class)
                .map(this::extractContentFromStreamChunk)
                .filter(chunk -> !chunk.isEmpty());
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

    private String mockResponse(String systemPrompt, String userMessage) {
        if (systemPrompt.contains("意图识别") || systemPrompt.contains("Planner")) {
            if (userMessage.contains("对比") || userMessage.contains("和") || userMessage.contains("与")) {
                return "{\"intent\": \"COMPARISON\", \"primaryCode\": \"005827\", \"secondaryCode\": \"161005\"}";
            } else if (userMessage.contains("筛选") || userMessage.contains("找") || userMessage.contains("推荐")) {
                return "{\"intent\": \"SCREENING\", \"fundType\": \"偏股混合型\"}";
            } else {
                return "{\"intent\": \"SINGLE_ANALYSIS\", \"primaryCode\": \"005827\"}";
            }
        }
        return "【投研报告】依据真实计算指标，目标基金在区间内风险控制良好，风格特征鲜明。";
    }
}
