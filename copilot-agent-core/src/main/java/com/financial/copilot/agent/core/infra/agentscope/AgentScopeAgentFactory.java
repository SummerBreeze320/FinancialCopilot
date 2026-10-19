package com.financial.copilot.agent.core.infra.agentscope;

import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.llm.config.LlmConfigManager;
import com.financial.copilot.agent.core.infra.memory.MemoryClient;
import com.financial.copilot.agent.core.infra.llm.dto.LlmResponse;
import com.financial.copilot.agent.core.infra.llm.dto.LlmSettingsDTO;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Creates and runs an isolated AgentScope ReActAgent for one DAG node invocation. */
@Component
public class AgentScopeAgentFactory {

    private final Supplier<LlmSettingsDTO> settingsSupplier;
    private final Function<LlmSettingsDTO, Model> modelProvider;
    private final MemoryClient memoryClient;
    private final com.financial.copilot.agent.core.infra.llm.execution.LlmExecutionGateway gateway;

    @Autowired
    public AgentScopeAgentFactory(LlmConfigManager configManager, MemoryClient memoryClient,
                                  com.financial.copilot.agent.core.infra.llm.execution.LlmExecutionGateway gateway) {
        this(configManager::getActiveSettings, AgentScopeAgentFactory::openAiCompatibleModel, memoryClient, gateway);
    }

    public AgentScopeAgentFactory(Supplier<LlmSettingsDTO> settingsSupplier,
                                  Function<LlmSettingsDTO, Model> modelProvider,
                                  MemoryClient memoryClient) {
        this(settingsSupplier, modelProvider, memoryClient,
                new com.financial.copilot.agent.core.infra.llm.execution.LlmExecutionGateway(
                        new com.financial.copilot.agent.core.infra.llm.execution.LlmCapacityLimiter(16)));
    }

    public AgentScopeAgentFactory(Supplier<LlmSettingsDTO> settingsSupplier,
                                  Function<LlmSettingsDTO, Model> modelProvider,
                                  MemoryClient memoryClient,
                                  com.financial.copilot.agent.core.infra.llm.execution.LlmExecutionGateway gateway) {
        this.settingsSupplier = Objects.requireNonNull(settingsSupplier);
        this.modelProvider = Objects.requireNonNull(modelProvider);
        this.memoryClient = memoryClient;
        this.gateway = gateway != null ? gateway : new com.financial.copilot.agent.core.infra.llm.execution.LlmExecutionGateway(
                new com.financial.copilot.agent.core.infra.llm.execution.LlmCapacityLimiter(16));
    }

    public Msg invoke(AgentDefinition definition, String prompt, NodeExecutionContext executionContext) {
        return invokeWithTrace(definition, prompt, executionContext).reply();
    }

    /**
     * 响应式非阻塞执行 AgentScope ReActAgent 调用链路
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

        LlmSettingsDTO settings = settingsSupplier.get();
        RuntimeContext runtimeContext = runtimeContext(executionContext);
        Map<String, List<io.agentscope.core.message.ToolResultBlock>> observations = new ConcurrentHashMap<>();
        Model meteredModel = new MeteredModel(modelProvider.apply(settings), settings, executionContext, observations, gateway);
        String enhancedSystemPrompt = enhanceWithProfile(definition.systemPrompt(), executionContext);
        ReActAgent agent = ReActAgent.builder()
                .name(definition.name())
                .description(definition.description())
                .sysPrompt(enhancedSystemPrompt)
                .model(meteredModel)
                .toolkit(definition.toolkit())
                .maxIters(definition.maxIterations())
                .generateOptions(generateOptions(settings))
                .build();
        executionContext.cancellationToken().onCancel(() -> agent.interrupt(runtimeContext));

        return agent.call(prompt == null ? "" : prompt, runtimeContext)
                .map(result -> {
                    if (result == null) {
                        throw new IllegalStateException(definition.name() + " returned no result");
                    }
                    Map<String, List<io.agentscope.core.message.ToolResultBlock>> snapshot = new java.util.HashMap<>();
                    observations.forEach((name, results) -> snapshot.put(name, List.copyOf(results)));
                    return new AgentScopeInvocation(result, Map.copyOf(snapshot));
                })
                .doFinally(signal -> agent.close());
    }

    public AgentScopeInvocation invokeWithTrace(AgentDefinition definition, String prompt,
                                                NodeExecutionContext executionContext) {
        AgentScopeInvocation invocation = invokeReactive(definition, prompt, executionContext).block();
        if (invocation == null) {
            throw new IllegalStateException(definition.name() + " returned null invocation");
        }
        return invocation;
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

    private String enhanceWithProfile(String baseSystemPrompt, NodeExecutionContext context) {
        if (context.request() == null || context.request().userId() == null) {
            return baseSystemPrompt;
        }
        try {
            String profilePrompt = memoryClient.getProfilePrompt(String.valueOf(context.request().userId()));
            if (profilePrompt == null || profilePrompt.isBlank()) {
                return baseSystemPrompt;
            }
            return baseSystemPrompt + "\n\n" + profilePrompt;
        } catch (Exception e) {
            return baseSystemPrompt;
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

    private static final class MeteredModel implements Model {
        private final Model delegate;
        private final LlmSettingsDTO settings;
        private final NodeExecutionContext context;
        private final Map<String, List<io.agentscope.core.message.ToolResultBlock>> observations;
        private final com.financial.copilot.agent.core.infra.llm.execution.LlmExecutionGateway gateway;
        private final java.util.Set<String> observedIds = ConcurrentHashMap.newKeySet();

        private MeteredModel(Model delegate, LlmSettingsDTO settings, NodeExecutionContext context,
                             Map<String, List<io.agentscope.core.message.ToolResultBlock>> observations,
                             com.financial.copilot.agent.core.infra.llm.execution.LlmExecutionGateway gateway) {
            this.delegate = Objects.requireNonNull(delegate);
            this.settings = Objects.requireNonNull(settings);
            this.context = context;
            this.observations = observations;
            this.gateway = Objects.requireNonNull(gateway);
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            messages.stream().flatMap(message -> message
                    .getContentBlocks(io.agentscope.core.message.ToolResultBlock.class).stream())
                    .filter(result -> observedIds.add(result.getId()))
                    .forEach(result -> observations.computeIfAbsent(result.getName(), ignored -> new CopyOnWriteArrayList<>())
                            .add(result));

            com.financial.copilot.agent.core.infra.llm.execution.LlmCallContext callContext =
                    new com.financial.copilot.agent.core.infra.llm.execution.LlmCallContext(
                            context.nodeId() != null ? context.nodeId() : "agent",
                            context.request() != null ? context.request().identity() : null,
                            delegate.getModelName(),
                            context.request() != null ? context.request().usageConsumer() : null,
                            null
                    );

            return gateway.execute(callContext, () -> delegate.stream(messages, tools, options))
                    .doOnNext(chatResponse -> {
                        if (context != null && context.chunkConsumer() != null && chatResponse != null) {
                            var content = chatResponse.getContent();
                            if (content != null) {
                                for (var block : content) {
                                    if (block instanceof io.agentscope.core.message.TextBlock textBlock) {
                                        String text = textBlock.getText();
                                        if (text != null && !text.isEmpty()) {
                                            context.chunkConsumer().accept(text);
                                        }
                                    }
                                }
                            }
                        }
                    });
        }

        @Override
        public String getModelName() {
            return delegate.getModelName();
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return delegate.supportsNativeStructuredOutput();
        }

        @Override
        public boolean supportsNativeStructuredOutputWithTools() {
            return delegate.supportsNativeStructuredOutputWithTools();
        }

        @Override
        public int getContextWindowSize() {
            return delegate.getContextWindowSize();
        }
    }
}
