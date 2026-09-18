package com.financial.copilot.agent.tools.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.tools.registry.ToolProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

/**
 * <h1>Wind AIMarket 开放工具市场客户端</h1>
 * <p>
 * 用于对接 Wind AIMarket 托管的通用 AI 工具 (如研报语义检索 fin_doc_searchV3 等)。
 * 采用 MCP tools/call 标准规范。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiMarketClient {

    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final ToolProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 调用 AIMarket MCP 目标工具。
     *
     * @param sessionId 当前会话 ID
     * @param toolName  工具名称
     * @param arguments 入参字典
     * @return 包含 tool_text, tool_payload, raw_response 的结果
     */
    public Map<String, Object> call(String sessionId, String toolName, Map<String, Object> arguments) {
        String serviceUrl = properties.getAimarket().getServiceUrl();
        String effectiveSessionId = StringUtils.hasText(sessionId) ? sessionId : "DEFAULT_SESSION";

        Map<String, Object> toolRequest = Map.of(
                "name", StringUtils.hasText(toolName) ? toolName : "",
                "arguments", arguments != null ? arguments : Map.of()
        );

        Map<String, Object> result = new HashMap<>();
        result.put("toolRequest", toolRequest);

        if (!StringUtils.hasText(serviceUrl)) {
            log.debug("WIND_AIMARKET_SERVICE_URL is not configured, returning empty result for {}", toolName);
            result.put("tool_text", "");
            result.put("tool_payload", null);
            return result;
        }

        try {
            // 阶段 1: initialize
            Map<String, Object> initBody = Map.of(
                    "jsonrpc", "2.0",
                    "id", "fund-research-aimarket-init",
                    "method", "initialize",
                    "params", Map.of(
                            "protocolVersion", PROTOCOL_VERSION,
                            "clientInfo", Map.of("name", "financial-copilot-agent", "version", "1.0.0"),
                            "capabilities", Map.of()
                    )
            );
            Map<String, Object> initResp = postJsonRpc(serviceUrl, effectiveSessionId, initBody);
            result.put("initialize", initResp);

            // 阶段 2: tools/call
            Map<String, Object> callBody = Map.of(
                    "jsonrpc", "2.0",
                    "id", "fund-research-aimarket-tool-call",
                    "method", "tools/call",
                    "params", toolRequest
            );
            Map<String, Object> rawResponse = postJsonRpc(serviceUrl, effectiveSessionId, callBody);
            result.put("raw_response", rawResponse);

            String toolText = extractToolText(rawResponse);
            result.put("tool_text", toolText);

            Object toolPayload = tryJsonLoads(toolText);
            result.put("tool_payload", toolPayload);

            return result;
        } catch (Exception e) {
            log.warn("AIMarket request failed for tool={}: {}", toolName, e.getMessage());
            result.put("tool_text", "");
            result.put("tool_payload", null);
            result.put("error", e.getMessage());
            return result;
        }
    }

    private Map<String, Object> postJsonRpc(String serviceUrl, String sessionId, Map<String, Object> body) {
        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            String responseText = webClientBuilder.build()
                    .post()
                    .uri(serviceUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Accept", "application/json, text/event-stream")
                    .header("wind.sessionid", sessionId)
                    .bodyValue(jsonBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(properties.getAimarket().getTimeoutSeconds()));

            return parseResponseText(responseText);
        } catch (Exception e) {
            throw new RuntimeException("AIMarket request failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> parseResponseText(String text) {
        if (!StringUtils.hasText(text)) {
            return Map.of();
        }
        String trimmed = text.trim();
        if (trimmed.contains("data:")) {
            return parseSseResponse(trimmed);
        }
        try {
            return objectMapper.readValue(trimmed, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("raw_response", trimmed);
        }
    }

    private Map<String, Object> parseSseResponse(String text) {
        List<String> messages = new ArrayList<>();
        List<String> currentLines = new ArrayList<>();

        for (String line : text.split("\\r?\\n")) {
            String stripped = line.trim();
            if (stripped.isEmpty()) {
                if (!currentLines.isEmpty()) {
                    messages.add(String.join("\n", currentLines));
                    currentLines.clear();
                }
                continue;
            }
            if (stripped.startsWith("data:")) {
                currentLines.add(stripped.substring(5).stripLeading());
            } else if (!stripped.startsWith("event:") && !stripped.startsWith("id:") && !stripped.startsWith(":")) {
                currentLines.add(stripped);
            }
        }
        if (!currentLines.isEmpty()) {
            messages.add(String.join("\n", currentLines));
        }

        for (String message : messages) {
            if (!StringUtils.hasText(message) || "[DONE]".equals(message)) {
                continue;
            }
            try {
                return objectMapper.readValue(message, new TypeReference<>() {});
            } catch (Exception ignored) {
            }
        }
        return Map.of("raw_response", text);
    }

    private String extractToolText(Map<String, Object> response) {
        if (response == null || response.isEmpty()) {
            return "";
        }
        Object contentObj = response.get("content");
        if (!(contentObj instanceof List<?>)) {
            Object resultObj = response.get("result");
            if (resultObj instanceof Map<?, ?> resMap) {
                contentObj = resMap.get("content");
            }
        }

        if (contentObj instanceof List<?> list && !list.isEmpty()) {
            Object first = list.getFirst();
            if (first instanceof Map<?, ?> map) {
                Object text = map.get("text");
                return text != null ? String.valueOf(text).trim() : "";
            }
        }
        return "";
    }

    private Object tryJsonLoads(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return objectMapper.readValue(text, Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}
