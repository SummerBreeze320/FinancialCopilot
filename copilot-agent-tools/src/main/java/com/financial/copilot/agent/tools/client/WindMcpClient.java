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
 * <h1>Wind MCP (Model Context Protocol) JSON-RPC 2.0 客户端</h1>
 * <p>
 * 遵循 MCP 协议标准交互机制：
 * <ul>
 *   <li>多 Server 动态路由 (支持 {@code wind-fund-data}, {@code wind-fund-holdings}, {@code wind-fund-analysis})</li>
 *   <li>双阶段握手：{@code initialize (protocolVersion: 2025-03-26)} -> {@code tools/call}</li>
 *   <li>双模响应兼容：同时支持普通 JSON 与 {@code text/event-stream} (SSE {@code data:} 流)</li>
 *   <li>金融专业内容提取：优先提取 {@code StructuredContent.Table.Markdown} 与 {@code Summary}</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WindMcpClient {

    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final ToolProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 执行 MCP 工具调用。
     *
     * @param sessionId 当前会话 ID
     * @param server    目标 Server 名称 (如 wind-fund-analysis)
     * @param toolName  MCP 工具名 (如 fund_get_similar)
     * @param arguments 入参字典
     * @return 包含 toolText, toolPayload, content, summary 等的标准化调用结果
     */
    public Map<String, Object> call(String sessionId, String server, String toolName, Map<String, Object> arguments) {
        String effectiveSessionId = StringUtils.hasText(sessionId) ? sessionId : "DEFAULT_SESSION";
        String serverUrl = resolveServerUrl(server);

        Map<String, Object> toolRequest = Map.of(
                "name", StringUtils.hasText(toolName) ? toolName : "",
                "arguments", arguments != null ? arguments : Map.of()
        );

        Map<String, Object> result = new HashMap<>();
        result.put("server", server);
        result.put("toolRequest", toolRequest);

        if (!StringUtils.hasText(serverUrl)) {
            log.debug("No server URL resolved for MCP server: {}, returning fallback", server);
            result.put("tool_text", "");
            result.put("content", "");
            result.put("summary", "");
            return result;
        }

        try {
            // 阶段 1: initialize 握手
            Map<String, Object> initBody = Map.of(
                    "jsonrpc", "2.0",
                    "id", "fund-copilot-mcp-init",
                    "method", "initialize",
                    "params", Map.of(
                            "protocolVersion", PROTOCOL_VERSION,
                            "clientInfo", Map.of("name", "financial-copilot-agent", "version", "1.0.0"),
                            "capabilities", Map.of()
                    )
            );
            Map<String, Object> initResp = postJsonRpc(serverUrl, effectiveSessionId, initBody);
            result.put("initialize", initResp);

            // 阶段 2: tools/call 执行
            Map<String, Object> callBody = Map.of(
                    "jsonrpc", "2.0",
                    "id", "fund-copilot-mcp-tool-call",
                    "method", "tools/call",
                    "params", toolRequest
            );
            Map<String, Object> rawResponse = postJsonRpc(serverUrl, effectiveSessionId, callBody);
            result.put("raw_response", rawResponse);

            // 阶段 3: 提炼返回内容
            String toolText = extractToolText(rawResponse);
            result.put("tool_text", toolText);

            Object toolPayload = tryJsonLoads(toolText);
            result.put("tool_payload", toolPayload);

            String businessContent = extractBusinessContent(toolPayload, toolText);
            result.put("content", businessContent);

            String summary = extractSummary(toolPayload);
            result.put("summary", summary);

            return result;
        } catch (Exception e) {
            log.warn("Wind MCP call failed for server={}, tool={}: {}", server, toolName, e.getMessage());
            result.put("tool_text", "");
            result.put("content", "");
            result.put("summary", "");
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * 枚举指定 MCP Server 上发布的所有工具列表。
     */
    public List<Map<String, Object>> listTools(String sessionId, String server) {
        String effectiveSessionId = StringUtils.hasText(sessionId) ? sessionId : "DEFAULT_SESSION";
        String serverUrl = resolveServerUrl(server);
        if (!StringUtils.hasText(serverUrl)) {
            return List.of();
        }

        try {
            // 先执行 initialize
            Map<String, Object> initBody = Map.of(
                    "jsonrpc", "2.0",
                    "id", "fund-copilot-mcp-init",
                    "method", "initialize",
                    "params", Map.of(
                            "protocolVersion", PROTOCOL_VERSION,
                            "clientInfo", Map.of("name", "financial-copilot-agent", "version", "1.0.0"),
                            "capabilities", Map.of()
                    )
            );
            postJsonRpc(serverUrl, effectiveSessionId, initBody);

            // 执行 tools/list
            Map<String, Object> listBody = Map.of(
                    "jsonrpc", "2.0",
                    "id", "fund-copilot-mcp-list-tools",
                    "method", "tools/list",
                    "params", Map.of()
            );
            Map<String, Object> resp = postJsonRpc(serverUrl, effectiveSessionId, listBody);
            Object resultObj = resp.get("result");
            if (resultObj instanceof Map<?, ?> resultMap) {
                Object toolsObj = resultMap.get("tools");
                if (toolsObj instanceof List<?> list) {
                    List<Map<String, Object>> tools = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> casted = (Map<String, Object>) map;
                            tools.add(casted);
                        }
                    }
                    return tools;
                }
            }
        } catch (Exception e) {
            log.warn("Wind MCP listTools failed for server={}: {}", server, e.getMessage());
        }
        return List.of();
    }

    private String resolveServerUrl(String server) {
        if (!StringUtils.hasText(server)) {
            return properties.getMcp().getBaseUrl();
        }
        Map<String, String> servers = properties.getMcp().getServers();
        if (servers != null && servers.containsKey(server)) {
            return servers.get(server);
        }
        return properties.getMcp().getBaseUrl() + "/" + server + "/mcp";
    }

    private Map<String, Object> postJsonRpc(String serverUrl, String sessionId, Map<String, Object> body) {
        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            String responseText = webClientBuilder.build()
                    .post()
                    .uri(serverUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Accept", "application/json, text/event-stream")
                    .header("wind.sessionid", sessionId)
                    .bodyValue(jsonBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(properties.getMcp().getTimeoutSeconds()));

            return parseResponseText(responseText);
        } catch (Exception e) {
            log.debug("HTTP JSON-RPC request to {} failed: {}", serverUrl, e.getMessage());
            throw new RuntimeException("MCP request failed: " + e.getMessage(), e);
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
            } else if (stripped.startsWith("event:") || stripped.startsWith("id:") || stripped.startsWith(":")) {
                // 跳过控制信令
            } else {
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

    @SuppressWarnings("unchecked")
    private String extractBusinessContent(Object payload, String fallbackText) {
        if (!(payload instanceof Map<?, ?> payloadMap)) {
            return fallbackText != null ? fallbackText : "";
        }

        try {
            Map<String, Object> data = (Map<String, Object>) payloadMap.get("data");
            if (data != null) {
                Map<String, Object> content = (Map<String, Object>) data.get("Content");
                if (content != null) {
                    Map<String, Object> structured = (Map<String, Object>) content.get("StructuredContent");
                    if (structured != null) {
                        Map<String, Object> table = (Map<String, Object>) structured.get("Table");
                        if (table != null && table.get("Markdown") != null) {
                            String markdown = String.valueOf(table.get("Markdown")).trim();
                            if (StringUtils.hasText(markdown)) {
                                return markdown;
                            }
                        }
                    }

                    Object contentItems = content.get("Content");
                    if (contentItems instanceof List<?> itemsList) {
                        List<String> texts = new ArrayList<>();
                        for (Object item : itemsList) {
                            if (item instanceof Map<?, ?> itemMap && itemMap.get("Text") != null) {
                                String t = String.valueOf(itemMap.get("Text")).trim();
                                if (StringUtils.hasText(t)) {
                                    texts.add(t);
                                }
                            }
                        }
                        if (!texts.isEmpty()) {
                            return String.join("\n\n", texts);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract structured business content: {}", e.getMessage());
        }

        return fallbackText != null ? fallbackText : "";
    }

    @SuppressWarnings("unchecked")
    private String extractSummary(Object payload) {
        if (!(payload instanceof Map<?, ?> payloadMap)) {
            return "";
        }
        try {
            Map<String, Object> data = (Map<String, Object>) payloadMap.get("data");
            if (data != null) {
                Map<String, Object> content = (Map<String, Object>) data.get("Content");
                if (content != null) {
                    Map<String, Object> structured = (Map<String, Object>) content.get("StructuredContent");
                    if (structured != null) {
                        Map<String, Object> summary = (Map<String, Object>) structured.get("Summary");
                        if (summary != null && summary.get("Text") != null) {
                            return String.valueOf(summary.get("Text")).trim();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
