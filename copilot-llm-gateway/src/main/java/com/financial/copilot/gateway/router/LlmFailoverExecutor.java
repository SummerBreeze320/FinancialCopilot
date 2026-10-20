package com.financial.copilot.gateway.router;

import com.financial.copilot.gateway.governance.GatewayAuditSink;
import com.financial.copilot.gateway.governance.GatewayCapacityLimiter;
import com.financial.copilot.gateway.model.LlmAuditRecord;
import com.financial.copilot.gateway.model.LlmGatewayRequest;
import com.financial.copilot.gateway.model.LlmGatewayResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * <h1>多链路故障自愈与重试执行器 (LlmFailoverExecutor)</h1>
 * <p>
 * 配合并发限流器，按照路由链依次发起尝试，捕获连接超时、429 与 5xx 异常后自动平滑转移至备用端点，
 * 记录 isFailover=true 并沉淀审计事实。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class LlmFailoverExecutor {

    private final GatewayCapacityLimiter capacityLimiter;
    private final GatewayAuditSink auditSink;
    private final EndpointInvoker invoker;

    @Autowired
    public LlmFailoverExecutor(GatewayCapacityLimiter capacityLimiter,
                               GatewayAuditSink auditSink) {
        this(capacityLimiter, auditSink, new DefaultWebClientEndpointInvoker());
    }

    public LlmFailoverExecutor(GatewayCapacityLimiter capacityLimiter,
                               GatewayAuditSink auditSink,
                               EndpointInvoker invoker) {
        this.capacityLimiter = Objects.requireNonNull(capacityLimiter, "capacityLimiter must not be null");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink must not be null");
        this.invoker = Objects.requireNonNull(invoker, "invoker must not be null");
    }

    /**
     * 顺序尝试端点链路，实现自愈降级
     *
     * @param request 网关请求
     * @param routeChain 候选端点链路
     * @return 包含自愈标记的响应 Mono
     */
    public Mono<LlmGatewayResponse> executeWithFailover(LlmGatewayRequest request, List<ProviderEndpointSpec> routeChain) {
        if (routeChain == null || routeChain.isEmpty()) {
            return Mono.error(new IllegalArgumentException("routeChain must not be empty"));
        }

        String traceId = resolveTraceId(request);

        return capacityLimiter.acquire()
                .flatMap(permit -> tryRouteChain(request, routeChain, 0, traceId, new ArrayList<>())
                        .doFinally(signal -> permit.release()));
    }

    private Mono<LlmGatewayResponse> tryRouteChain(LlmGatewayRequest request,
                                                  List<ProviderEndpointSpec> routeChain,
                                                  int index,
                                                  String traceId,
                                                  List<String> errorHistory) {
        if (index >= routeChain.size()) {
            String combinedErrors = String.join("; ", errorHistory);
            log.error("[LlmFailoverExecutor] 所有候选链路均执行失败: traceId={}, errors={}", traceId, combinedErrors);
            return Mono.error(new RuntimeException("All provider routes failed: " + combinedErrors));
        }

        ProviderEndpointSpec currentSpec = routeChain.get(index);
        boolean isFailover = index > 0;
        long startNanos = System.currentTimeMillis();

        return invoker.invoke(request, currentSpec)
                .map(resp -> {
                    long latency = System.currentTimeMillis() - startNanos;
                    LlmGatewayResponse enriched = LlmGatewayResponse.builder()
                            .content(resp.content())
                            .reasoningContent(resp.reasoningContent())
                            .actualModel(resp.actualModel() != null ? resp.actualModel() : currentSpec.modelName())
                            .actualProvider(resp.actualProvider() != null ? resp.actualProvider() : currentSpec.provider())
                            .promptTokens(resp.promptTokens())
                            .completionTokens(resp.completionTokens())
                            .totalTokens(resp.totalTokens())
                            .latencyMs(latency)
                            .isFailover(isFailover)
                            .build();

                    recordAudit(traceId, request, enriched, null);
                    return enriched;
                })
                .onErrorResume(ex -> {
                    long latency = System.currentTimeMillis() - startNanos;
                    String err = currentSpec.provider() + "(" + currentSpec.modelName() + ") -> " + ex.getMessage();
                    errorHistory.add(err);
                    log.warn("[LlmFailoverExecutor] 链路尝试失败: traceId={}, provider={}, error={}, 准备切换至备选链路",
                            traceId, currentSpec.provider(), ex.getMessage());

                    recordAudit(traceId, request, null, ex.getMessage());

                    return tryRouteChain(request, routeChain, index + 1, traceId, errorHistory);
                });
    }

    private void recordAudit(String traceId, LlmGatewayRequest request, LlmGatewayResponse response, String error) {
        LlmAuditRecord record = LlmAuditRecord.builder()
                .traceId(traceId)
                .scene(request.scene())
                .actualModel(response != null ? response.actualModel() : "unknown")
                .actualProvider(response != null ? response.actualProvider() : "unknown")
                .promptTokens(response != null ? response.promptTokens() : 0)
                .completionTokens(response != null ? response.completionTokens() : 0)
                .totalTokens(response != null ? response.totalTokens() : 0)
                .latencyMs(response != null ? response.latencyMs() : 0)
                .isFailover(response != null && response.isFailover())
                .errorMessage(error)
                .timestamp(Instant.now())
                .build();

        auditSink.record(record);
        if (request.auditCallback() != null) {
            try {
                request.auditCallback().accept(record);
            } catch (Exception e) {
                log.warn("[LlmFailoverExecutor] 审计回调执行异常: {}", e.getMessage());
            }
        }
    }

    private String resolveTraceId(LlmGatewayRequest request) {
        if (request != null && request.metadata() != null && request.metadata().containsKey("traceId")) {
            return String.valueOf(request.metadata().get("traceId"));
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 单端点调用接口 (供测试 Mock 或自定义客户端注入)
     */
    @FunctionalInterface
    public interface EndpointInvoker {
        Mono<LlmGatewayResponse> invoke(LlmGatewayRequest request, ProviderEndpointSpec spec);
    }

    /**
     * 默认基于 WebClient 的 OpenAI-compatible 端点调用器
     */
    public static class DefaultWebClientEndpointInvoker implements EndpointInvoker {
        private final com.financial.copilot.gateway.transport.LlmDynamicWebClientFactory webClientFactory;

        public DefaultWebClientEndpointInvoker() {
            this(new com.financial.copilot.gateway.transport.LlmDynamicWebClientFactory());
        }

        public DefaultWebClientEndpointInvoker(com.financial.copilot.gateway.transport.LlmDynamicWebClientFactory webClientFactory) {
            this.webClientFactory = webClientFactory != null ? webClientFactory : new com.financial.copilot.gateway.transport.LlmDynamicWebClientFactory();
        }

        @Override
        public Mono<LlmGatewayResponse> invoke(LlmGatewayRequest request, ProviderEndpointSpec spec) {
            WebClient webClient = webClientFactory.getOrCreateWebClient(spec.baseUrl(), spec.apiKey());
            String uri = spec.baseUrl().endsWith("/") ? "chat/completions" : "/chat/completions";

            List<Map<String, String>> messages = new ArrayList<>();
            if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                messages.add(Map.of("role", "system", "content", request.systemPrompt()));
            }
            if (request.userPrompt() != null) {
                messages.add(Map.of("role", "user", "content", request.userPrompt()));
            }

            Map<String, Object> body = Map.of(
                    "model", spec.modelName(),
                    "messages", messages,
                    "temperature", request.temperature() != null ? request.temperature() : 0.7,
                    "stream", false
            );

            return webClient.post()
                    .uri(uri)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .map(map -> {
                        String text = "";
                        int promptTok = 0, compTok = 0, totalTok = 0;
                        if (map.containsKey("choices")) {
                            List<?> choices = (List<?>) map.get("choices");
                            if (choices != null && !choices.isEmpty()) {
                                Map<?, ?> first = (Map<?, ?>) choices.get(0);
                                if (first.containsKey("message")) {
                                    Map<?, ?> msg = (Map<?, ?>) first.get("message");
                                    Object contentObj = msg.get("content");
                                    text = contentObj != null ? contentObj.toString() : "";
                                }
                            }
                        }
                        if (map.containsKey("usage")) {
                            Map<?, ?> usage = (Map<?, ?>) map.get("usage");
                            if (usage != null) {
                                Object pt = usage.get("prompt_tokens");
                                promptTok = pt instanceof Number n ? n.intValue() : 0;
                                Object ct = usage.get("completion_tokens");
                                compTok = ct instanceof Number n ? n.intValue() : 0;
                                Object tt = usage.get("total_tokens");
                                totalTok = tt instanceof Number n ? n.intValue() : 0;
                            }
                        }
                        return LlmGatewayResponse.builder()
                                .content(text)
                                .actualModel(spec.modelName())
                                .actualProvider(spec.provider())
                                .promptTokens(promptTok)
                                .completionTokens(compTok)
                                .totalTokens(totalTok)
                                .isFailover(false)
                                .build();
                    });
        }
    }
}
