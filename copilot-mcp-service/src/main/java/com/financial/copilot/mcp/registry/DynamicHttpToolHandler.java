package com.financial.copilot.mcp.registry;

import com.financial.copilot.mcp.protocol.McpToolCallResult;
import com.financial.copilot.mcp.protocol.McpToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * <h1>动态 HTTP REST 工具处理器</h1>
 * <p>
 * 基于 WebClient 将 MCP 工具调用请求非阻塞转发至外部第三方 API 或微服务 (如 copilot-ner-service, copilot-memory-service)。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
public class DynamicHttpToolHandler implements McpToolHandler {

    private final DynamicToolConfig config;
    private final McpToolDefinition definition;
    private final WebClient webClient;

    public DynamicHttpToolHandler(DynamicToolConfig config, WebClient.Builder webClientBuilder) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.definition = config.toDefinition();
        this.webClient = webClientBuilder.build();
    }

    @Override
    public McpToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public Mono<McpToolCallResult> execute(Map<String, Object> arguments) {
        log.info("[MCP DYNAMIC-TOOL] 转发调用外部工具: name={}, endpoint={}", config.getName(), config.getEndpoint());

        HttpMethod method = "GET".equalsIgnoreCase(config.getMethod()) ? HttpMethod.GET : HttpMethod.POST;
        Duration timeout = Duration.ofMillis(config.getTimeoutMs() > 0 ? config.getTimeoutMs() : 10000);

        URI targetUri;
        try {
            if (method == HttpMethod.GET && arguments != null && !arguments.isEmpty()) {
                UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(config.getEndpoint());
                arguments.forEach((k, v) -> {
                    if (v != null) builder.queryParam(k, v.toString());
                });
                targetUri = builder.build().toUri();
            } else {
                targetUri = URI.create(config.getEndpoint());
            }
        } catch (Exception ex) {
            log.error("[MCP DYNAMIC-TOOL] 构建请求 URI 失败: endpoint={}, err={}", config.getEndpoint(), ex.getMessage());
            return Mono.just(McpToolCallResult.error("非法 endpoint URI: " + config.getEndpoint()));
        }

        WebClient.RequestBodySpec requestSpec = webClient.method(method)
                .uri(targetUri)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN);

        if (config.getHeaders() != null) {
            config.getHeaders().forEach(requestSpec::header);
        }

        if (method == HttpMethod.POST && arguments != null) {
            requestSpec.contentType(MediaType.APPLICATION_JSON).bodyValue(arguments);
        }

        return requestSpec.retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .map(McpToolCallResult::success)
                .onErrorResume(ex -> {
                    log.error("[MCP DYNAMIC-TOOL] 外部工具调用失败: name={}, err={}", config.getName(), ex.getMessage());
                    return Mono.just(McpToolCallResult.error("调用外部工具 " + config.getName() + " 失败: " + ex.getMessage()));
                });
    }
}
