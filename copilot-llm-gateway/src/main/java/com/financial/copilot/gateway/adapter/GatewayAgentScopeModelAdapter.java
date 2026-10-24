package com.financial.copilot.gateway.adapter;

import com.financial.copilot.gateway.governance.GatewayAuditSink;
import com.financial.copilot.gateway.governance.GatewayCapacityLimiter;
import com.financial.copilot.gateway.model.LlmAuditRecord;
import com.financial.copilot.gateway.model.LlmRoutingScene;
import com.financial.copilot.gateway.router.LlmRouter;
import com.financial.copilot.gateway.router.ProviderEndpointSpec;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * <h1>AgentScope 大模型统一适配器 (Gateway AgentScope Model Adapter)</h1>
 * <p>
 * 职责：实现 AgentScope 框架的 {@link Model} 接口契约，
 * 将 AgentScope 底层流式调用透明融入 LLM 网关的并发限流、路由分发、
 * 故障自愈 Failover 以及全链路审计管道中。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
public class GatewayAgentScopeModelAdapter implements Model {

    private final LlmRoutingScene scene;
    private final String preferredModel;
    private final LlmRouter router;
    private final GatewayCapacityLimiter capacityLimiter;
    private final GatewayAuditSink auditSink;
    private final Function<ProviderEndpointSpec, Model> delegateFactory;
    private final io.micrometer.tracing.Tracer tracer;

    /**
     * 生产环境标准构造器
     *
     * @param scene           路由场景
     * @param preferredModel  首选模型（可为 null）
     * @param router          路由器
     * @param capacityLimiter 并发容量限流器
     * @param auditSink       审计日志接收器
     */
    public GatewayAgentScopeModelAdapter(
            LlmRoutingScene scene,
            String preferredModel,
            LlmRouter router,
            GatewayCapacityLimiter capacityLimiter,
            GatewayAuditSink auditSink
    ) {
        this(scene, preferredModel, router, capacityLimiter, auditSink, GatewayAgentScopeModelAdapter::createDefaultDelegate, null);
    }

    /**
     * 可扩展/测试构造器
     *
     * @param scene           路由场景
     * @param preferredModel  首选模型（可为 null）
     * @param router          路由器
     * @param capacityLimiter 并发容量限流器
     * @param auditSink       审计日志接收器
     * @param delegateFactory 下层 Model 实例构造函数（用于单元测试 Mock）
     */
    public GatewayAgentScopeModelAdapter(
            LlmRoutingScene scene,
            String preferredModel,
            LlmRouter router,
            GatewayCapacityLimiter capacityLimiter,
            GatewayAuditSink auditSink,
            Function<ProviderEndpointSpec, Model> delegateFactory
    ) {
        this(scene, preferredModel, router, capacityLimiter, auditSink, delegateFactory, null);
    }

    public GatewayAgentScopeModelAdapter(
            LlmRoutingScene scene,
            String preferredModel,
            LlmRouter router,
            GatewayCapacityLimiter capacityLimiter,
            GatewayAuditSink auditSink,
            Function<ProviderEndpointSpec, Model> delegateFactory,
            io.micrometer.tracing.Tracer tracer
    ) {
        this.scene = Objects.requireNonNull(scene, "scene must not be null");
        this.preferredModel = preferredModel;
        this.router = Objects.requireNonNull(router, "router must not be null");
        this.capacityLimiter = Objects.requireNonNull(capacityLimiter, "capacityLimiter must not be null");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink must not be null");
        this.delegateFactory = Objects.requireNonNull(delegateFactory, "delegateFactory must not be null");
        this.tracer = tracer;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        List<ProviderEndpointSpec> routes = router.resolveRoute(scene, preferredModel);
        if (routes == null || routes.isEmpty()) {
            return Flux.error(new IllegalStateException("No available LLM routes configured for scene: " + scene));
        }

        String traceId = resolveTraceId();
        long startTime = System.currentTimeMillis();

        return Flux.usingWhen(
                capacityLimiter.acquire(),
                permit -> executeRouteChainWithFailover(routes, messages, tools, options, traceId, startTime),
                permit -> Mono.fromRunnable(permit::release)
        );
    }

    private String resolveTraceId() {
        if (tracer != null) {
            io.micrometer.tracing.Span current = tracer.currentSpan();
            if (current != null && current.context() != null && current.context().traceId() != null) {
                return current.context().traceId();
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private Flux<ChatResponse> executeRouteChainWithFailover(
            List<ProviderEndpointSpec> routes,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options,
            String traceId,
            long startTime
    ) {
        return executeAttempt(routes, 0, messages, tools, options, traceId, startTime, new ArrayList<>());
    }

    private Flux<ChatResponse> executeAttempt(
            List<ProviderEndpointSpec> routes,
            int index,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options,
            String traceId,
            long startTime,
            List<String> errorAccumulator
    ) {
        if (index >= routes.size()) {
            String msg = "[GatewayAgentScopeModelAdapter] 所有候选链路均执行失败: traceId=" + traceId
                    + ", errors=" + String.join("; ", errorAccumulator);
            log.error(msg);
            long latency = System.currentTimeMillis() - startTime;
            auditSink.record(LlmAuditRecord.builder()
                    .traceId(traceId)
                    .scene(scene)
                    .latencyMs(latency)
                    .errorMessage(msg)
                    .timestamp(java.time.Instant.now())
                    .build());
            return Flux.error(new IllegalStateException(msg));
        }

        ProviderEndpointSpec spec = routes.get(index);
        boolean isFailover = index > 0;
        AtomicInteger totalTokensHolder = new AtomicInteger(0);
        AtomicInteger promptTokensHolder = new AtomicInteger(0);
        AtomicInteger completionTokensHolder = new AtomicInteger(0);

        Model delegate = delegateFactory.apply(spec);

        return delegate.stream(messages, tools, options)
                .doOnNext(chatResponse -> {
                    if (chatResponse != null && chatResponse.getUsage() != null) {
                        var usage = chatResponse.getUsage();
                        if (usage.getTotalTokens() > 0) {
                            totalTokensHolder.set(usage.getTotalTokens());
                            promptTokensHolder.set(usage.getInputTokens());
                            completionTokensHolder.set(usage.getOutputTokens());
                        }
                    }
                })
                .doOnComplete(() -> {
                    long latency = System.currentTimeMillis() - startTime;
                    auditSink.record(LlmAuditRecord.builder()
                            .traceId(traceId)
                            .scene(scene)
                            .actualModel(spec.modelName())
                            .actualProvider(spec.provider())
                            .promptTokens(promptTokensHolder.get())
                            .completionTokens(completionTokensHolder.get())
                            .totalTokens(totalTokensHolder.get())
                            .latencyMs(latency)
                            .isFailover(isFailover)
                            .timestamp(java.time.Instant.now())
                            .build());
                })
                .onErrorResume(ex -> {
                    String errDetail = spec.provider() + "(" + spec.modelName() + ") -> " + ex.getMessage();
                    errorAccumulator.add(errDetail);
                    log.warn("[GatewayAgentScopeModelAdapter] 链路尝试失败: traceId={}, provider={}, error={}, 准备切换至备选链路",
                            traceId, spec.provider(), ex.getMessage());
                    return executeAttempt(routes, index + 1, messages, tools, options, traceId, startTime, errorAccumulator);
                });
    }

    private static Model createDefaultDelegate(ProviderEndpointSpec spec) {
        var builder = OpenAIChatModel.builder()
                .apiKey(spec.apiKey())
                .modelName(spec.modelName())
                .stream(true)
                .nativeStructuredOutput(false)
                .nativeStructuredOutputWithTools(false);
        if (spec.baseUrl() != null && !spec.baseUrl().isBlank()) {
            builder.baseUrl(spec.baseUrl());
        }
        return builder.build();
    }

    @Override
    public String getModelName() {
        return preferredModel != null ? preferredModel : scene.name();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return false;
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return false;
    }

    @Override
    public int getContextWindowSize() {
        return 128000;
    }
}
