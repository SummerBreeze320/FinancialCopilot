package com.financial.copilot.agent.core.infra.agentscope;

import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.llm.execution.LlmCallContext;
import com.financial.copilot.agent.core.infra.llm.execution.LlmCapacityLimiter;
import com.financial.copilot.agent.core.infra.llm.execution.LlmExecutionGateway;
import com.financial.copilot.agent.core.infra.memory.MemoryClient;
import com.financial.copilot.gateway.client.LlmGatewayClient;
import com.financial.copilot.gateway.config.LlmConfigManager;
import com.financial.copilot.gateway.model.LlmResponse;
import com.financial.copilot.gateway.model.LlmRoutingScene;
import com.financial.copilot.gateway.model.LlmSettingsDTO;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import com.financial.copilot.agent.core.infra.agentscope.loop.AgentLoopSupervisor;
import com.financial.copilot.agent.core.infra.agentscope.loop.AgentTerminationStatus;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * <h1>AgentScope 2.0 官方规范单例无状态智能体工厂 (AgentScopeAgentFactory)</h1>
 * <p>
 * 遵循 AgentScope 2.0 官方无状态单例最佳实践与平台治理规范：
 * <ul>
 *     <li>基于 {@link AgentDefinition} 单例缓存与全局复用 {@link ReActAgent} 实例，消除每次请求重建开销；</li>
 *     <li>系统提示词静态化，用户个性化 Profile 通过运行时用户提示词安全注入；</li>
 *     <li>基于 Reactor 上下文传播 ({@link Flux#deferContextual}) 穿透响应式线程切换，安全绑定瞬态请求数据；</li>
 *     <li>模型层采用纯无状态代理 {@link GovernedModel}，单次运行上下文由 {@link RuntimeContext} 外部透传；</li>
 *     <li>常驻单例，废弃 {@code agent.close()}，支持高并发无状态复用与精确会话中断。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class AgentScopeAgentFactory {

    private final Supplier<LlmSettingsDTO> settingsSupplier;
    private final Function<LlmSettingsDTO, Model> modelProvider;
    private final MemoryClient memoryClient;
    private final LlmExecutionGateway gateway;
    private final Model governedModel;

    private final Map<AgentDefinition, ReActAgent> agentCache = new ConcurrentHashMap<>();

    @Autowired
    public AgentScopeAgentFactory(LlmConfigManager configManager, MemoryClient memoryClient,
                                  LlmExecutionGateway gateway,
                                  @Autowired(required = false) LlmGatewayClient gatewayClient) {
        this(configManager::getActiveSettings,
                settings -> gatewayClient != null
                        ? gatewayClient.createAgentScopeModel(
                                settings != null && settings.isEnableThinking() ? LlmRoutingScene.REASONING : LlmRoutingScene.STANDARD,
                                settings != null ? settings.resolveEffectiveModel() : null)
                        : openAiCompatibleModel(settings),
                memoryClient,
                gateway);
    }

    public AgentScopeAgentFactory(LlmConfigManager configManager, MemoryClient memoryClient,
                                  LlmExecutionGateway gateway) {
        this(configManager, memoryClient, gateway, null);
    }

    public AgentScopeAgentFactory(Supplier<LlmSettingsDTO> settingsSupplier,
                                  Function<LlmSettingsDTO, Model> modelProvider,
                                  MemoryClient memoryClient) {
        this(settingsSupplier, modelProvider, memoryClient,
                new LlmExecutionGateway(new LlmCapacityLimiter(16)));
    }

    public AgentScopeAgentFactory(Supplier<LlmSettingsDTO> settingsSupplier,
                                  Function<LlmSettingsDTO, Model> modelProvider,
                                  MemoryClient memoryClient,
                                  LlmExecutionGateway gateway) {
        this.settingsSupplier = Objects.requireNonNull(settingsSupplier, "settingsSupplier cannot be null");
        this.modelProvider = Objects.requireNonNull(modelProvider, "modelProvider cannot be null");
        this.memoryClient = memoryClient;
        this.gateway = gateway != null ? gateway : new LlmExecutionGateway(new LlmCapacityLimiter(16));
        this.governedModel = new GovernedModel();
    }

    public Msg invoke(AgentDefinition definition, String prompt, NodeExecutionContext executionContext) {
        return invokeWithTrace(definition, prompt, executionContext).reply();
    }

    /**
     * 响应式非阻塞执行 AgentScope ReActAgent 调用链路（基于单例无状态复用）。
     *
     * @param definition       Agent 拓扑元数据定义
     * @param prompt           用户/上游指令提示词
     * @param executionContext 节点运行时上下文
     * @return 包含最终响应与工具观测明细的响应式 Mono 容器
     */
    public Mono<AgentScopeInvocation> invokeReactive(AgentDefinition definition, String prompt,
                                                     NodeExecutionContext executionContext) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(executionContext, "executionContext");
        try {
            executionContext.cancellationToken().throwIfCancelled();
        } catch (Exception e) {
            return Mono.error(e);
        }

        RuntimeContext runtimeContext = runtimeContext(executionContext);
        Map<String, List<ToolResultBlock>> observations = new ConcurrentHashMap<>();
        AgentLoopSupervisor loopSupervisor = new AgentLoopSupervisor(definition.maxIterations(), 2, 2);
        CallContext callCtx = new CallContext(executionContext, observations, loopSupervisor);
        runtimeContext.put(CallContext.class, callCtx);
        runtimeContext.put(AgentLoopSupervisor.class, loopSupervisor);

        // 用户个性化 Profile 注入（在请求 Prompt 中组装，保持 Agent 的 systemPrompt 纯净单例可共享）
        String effectivePrompt = enhancePromptWithProfile(prompt, executionContext);

        // 从单例池获取或构建单例 ReActAgent
        ReActAgent agent = getOrCreateAgent(definition);

        executionContext.cancellationToken().onCancel(() -> {
            loopSupervisor.recordCancellation();
            agent.interrupt(runtimeContext);
        });

        return Mono.defer(() -> {
            AutoCloseable scope = ScopedCallContext.open(callCtx);
            return agent.call(effectivePrompt == null ? "" : effectivePrompt, runtimeContext)
                    .map(result -> {
                        if (result == null) {
                            throw new IllegalStateException(definition.name() + " returned no result");
                        }
                        Map<String, List<ToolResultBlock>> snapshot = new HashMap<>();
                        observations.forEach((name, results) -> snapshot.put(name, List.copyOf(results)));
                        AgentTerminationStatus status = loopSupervisor.getTerminationStatus() != null
                                ? loopSupervisor.getTerminationStatus()
                                : AgentTerminationStatus.TASK_COMPLETED;
                        return new AgentScopeInvocation(
                                result,
                                Map.copyOf(snapshot),
                                status.isDegraded(),
                                status
                        );
                    })
                    .contextWrite(ctx -> ctx.put(AgentBase.RUNTIME_CONTEXT_KEY, runtimeContext))
                    .doFinally(signal -> {
                        try {
                            scope.close();
                        } catch (Exception ignored) {
                        }
                    });
        });
    }

    public AgentScopeInvocation invokeWithTrace(AgentDefinition definition, String prompt,
                                                NodeExecutionContext executionContext) {
        AgentScopeInvocation invocation = invokeReactive(definition, prompt, executionContext).block();
        if (invocation == null) {
            throw new IllegalStateException(definition.name() + " returned null invocation");
        }
        return invocation;
    }

    Map<AgentDefinition, ReActAgent> getAgentCache() {
        return java.util.Collections.unmodifiableMap(agentCache);
    }

    private ReActAgent getOrCreateAgent(AgentDefinition definition) {
        return agentCache.computeIfAbsent(definition, def -> {
            LlmSettingsDTO settings = settingsSupplier.get();
            return ReActAgent.builder()
                    .name(def.name())
                    .description(def.description())
                    .sysPrompt(def.systemPrompt()) // 静态角色设定，不包含个性化画像
                    .model(governedModel)          // 全局单例无状态治理模型代理
                    .toolkit(def.toolkit())
                    .maxIters(def.maxIterations())
                    .generateOptions(generateOptions(settings))
                    .build();
        });
    }

    RuntimeContext runtimeContext(NodeExecutionContext context) {
        var request = context.request();
        String workflowRunId = request != null && request.identity() != null ? request.identity().workflowRunId()
                : (request != null && request.runId() != null ? request.runId() : "anon");
        int attempt = context.attempt() > 0 ? context.attempt() : 1;
        String agentRunId = workflowRunId + ":" + context.nodeId() + ":" + attempt;
        AgentInvocationIdentity invocationIdentity = new AgentInvocationIdentity(
                workflowRunId, agentRunId, context.nodeId(), attempt);

        RuntimeContext.Builder builder = RuntimeContext.builder()
                .put("runId", agentRunId)
                .put("workflowRunId", workflowRunId)
                .put(NodeExecutionContext.class, context)
                .put(AgentInvocationIdentity.class, invocationIdentity);
        if (context.input() != null) {
            builder.put(com.financial.copilot.agent.core.infra.dag.runtime.NodeInput.class, context.input());
        }
        if (request != null) {
            if (request.sessionKey() != null) {
                builder.sessionId(request.sessionKey());
            }
            if (request.userId() != null) {
                builder.userId(String.valueOf(request.userId()));
            }
            if (request.identity() != null) {
                builder.put(com.financial.copilot.agent.core.infra.context.ExecutionIdentity.class, request.identity());
            }
        }
        return builder.build();
    }

    private String enhancePromptWithProfile(String basePrompt, NodeExecutionContext context) {
        if (context.request() == null || context.request().userId() == null || memoryClient == null) {
            return basePrompt;
        }
        try {
            String profilePrompt = memoryClient.getProfilePrompt(String.valueOf(context.request().userId()));
            if (profilePrompt == null || profilePrompt.isBlank()) {
                return basePrompt;
            }
            String base = basePrompt == null ? "" : basePrompt;
            return base + "\n\n[用户个性化投资偏好与背景]:\n" + profilePrompt;
        } catch (Exception e) {
            return basePrompt;
        }
    }

    private static GenerateOptions generateOptions(LlmSettingsDTO settings) {
        return GenerateOptions.builder()
                .temperature(settings.resolveTemperature())
                .topP(settings.getTopP())
                .maxTokens(settings.resolveMaxTokens())
                .parallelToolCalls(false)
                .build();
    }

    private static Model openAiCompatibleModel(LlmSettingsDTO settings) {
        if (settings == null) throw new IllegalStateException("No active LLM settings");
        String apiKey = settings.getCustomApiKey();
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("placeholder")) {
            throw new IllegalStateException("AgentScope ReAct requires a configured LLM API key");
        }
        var builder = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(settings.resolveEffectiveModel())
                .stream(false)
                .nativeStructuredOutput(false)
                .nativeStructuredOutputWithTools(false);
        if (settings.getCustomBaseUrl() != null && !settings.getCustomBaseUrl().isBlank()) {
            builder.baseUrl(settings.getCustomBaseUrl());
        }
        return builder.build();
    }

    public record AgentDefinition(
            String name,
            String description,
            String systemPrompt,
            Toolkit toolkit,
            int maxIterations
    ) {
        public AgentDefinition {
            Objects.requireNonNull(name, "name");
            description = description == null ? name : description;
            Objects.requireNonNull(systemPrompt, "systemPrompt");
            Objects.requireNonNull(toolkit, "toolkit");
            if (maxIterations < 1) throw new IllegalArgumentException("maxIterations must be positive");
        }
    }

    /**
     * <h1>单例无状态治理模型代理 (GovernedModel)</h1>
     * <p>
     * 不持有任何请求特定字段，所有运行时状态由 Reactor 响应式上下文与 {@link RuntimeContext} 动态提供。
     * 统一负责调用 LLM 网关限流、防死循环结果校验与流式 Token 推送。
     * </p>
     */
    private final class GovernedModel implements Model {

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.deferContextual(contextView -> {
                RuntimeContext rc = contextView.hasKey(AgentBase.RUNTIME_CONTEXT_KEY)
                        ? contextView.get(AgentBase.RUNTIME_CONTEXT_KEY)
                        : null;
                CallContext callCtx = (rc != null && rc.get(CallContext.class) != null)
                        ? rc.get(CallContext.class)
                        : ScopedCallContext.current();

                NodeExecutionContext context = callCtx != null ? callCtx.executionContext() : null;
                Map<String, List<ToolResultBlock>> observations = callCtx != null ? callCtx.observations() : new ConcurrentHashMap<>();
                AgentLoopSupervisor loopSupervisor = callCtx != null ? callCtx.loopSupervisor() : null;
                Set<String> observedIds = callCtx != null ? callCtx.observedIds() : ConcurrentHashMap.newKeySet();

                if (loopSupervisor != null) {
                    List<ToolResultBlock> newResults = new ArrayList<>();
                    messages.stream().flatMap(message -> message
                            .getContentBlocks(ToolResultBlock.class).stream())
                            .filter(result -> observedIds.add(result.getId()))
                            .forEach(result -> {
                                observations.computeIfAbsent(result.getName(), ignored -> new CopyOnWriteArrayList<>()).add(result);
                                newResults.add(result);
                            });

                    if (!newResults.isEmpty()) {
                        loopSupervisor.recordObservations(newResults);
                    }
                }

                LlmSettingsDTO settings = settingsSupplier.get();
                Model delegate = modelProvider.apply(settings);

                String nodeId = (context != null && context.nodeId() != null) ? context.nodeId() : "agent";
                com.financial.copilot.agent.core.infra.context.ExecutionIdentity identity =
                        (context != null && context.request() != null) ? context.request().identity() : null;
                Consumer<LlmResponse> usageConsumer =
                        (context != null && context.request() != null) ? context.request().usageConsumer() : null;

                LlmCallContext callContext = new LlmCallContext(
                        nodeId,
                        identity,
                        delegate.getModelName(),
                        usageConsumer,
                        null
                );

                return gateway.execute(callContext, () -> delegate.stream(messages, tools, options))
                        .map(chatResponse -> loopSupervisor != null ? loopSupervisor.inspectAndGuard(chatResponse, observations) : chatResponse)
                        .doOnNext(chatResponse -> {
                            if (context != null && context.chunkConsumer() != null && chatResponse != null) {
                                var content = chatResponse.getContent();
                                if (content != null) {
                                    for (var block : content) {
                                        if (block instanceof TextBlock textBlock) {
                                            String text = textBlock.getText();
                                            if (text != null && !text.isEmpty()) {
                                                context.chunkConsumer().accept(text);
                                            }
                                        }
                                    }
                                }
                            }
                        });
            });
        }

        @Override
        public String getModelName() {
            Model delegate = modelProvider.apply(settingsSupplier.get());
            return delegate != null ? delegate.getModelName() : "governed-model";
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            Model delegate = modelProvider.apply(settingsSupplier.get());
            return delegate != null && delegate.supportsNativeStructuredOutput();
        }

        @Override
        public boolean supportsNativeStructuredOutputWithTools() {
            Model delegate = modelProvider.apply(settingsSupplier.get());
            return delegate != null && delegate.supportsNativeStructuredOutputWithTools();
        }

        @Override
        public int getContextWindowSize() {
            Model delegate = modelProvider.apply(settingsSupplier.get());
            return delegate != null ? delegate.getContextWindowSize() : 8192;
        }
    }
}
