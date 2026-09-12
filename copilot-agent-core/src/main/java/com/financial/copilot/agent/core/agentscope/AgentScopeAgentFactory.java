package com.financial.copilot.agent.core.agentscope;

import com.financial.copilot.agent.core.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.llm.config.LlmConfigManager;
import com.financial.copilot.agent.core.llm.dto.LlmResponse;
import com.financial.copilot.agent.core.llm.dto.LlmSettingsDTO;
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

    @Autowired
    public AgentScopeAgentFactory(LlmConfigManager configManager) {
        this(configManager::getActiveSettings, AgentScopeAgentFactory::openAiCompatibleModel);
    }

    public AgentScopeAgentFactory(Supplier<LlmSettingsDTO> settingsSupplier,
                                  Function<LlmSettingsDTO, Model> modelProvider) {
        this.settingsSupplier = Objects.requireNonNull(settingsSupplier);
        this.modelProvider = Objects.requireNonNull(modelProvider);
    }

    public Msg invoke(AgentDefinition definition, String prompt, NodeExecutionContext executionContext) {
        return invokeWithTrace(definition, prompt, executionContext).reply();
    }

    public AgentScopeInvocation invokeWithTrace(AgentDefinition definition, String prompt,
                                                NodeExecutionContext executionContext) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(executionContext, "executionContext");
        executionContext.cancellationToken().throwIfCancelled();

        LlmSettingsDTO settings = settingsSupplier.get();
        RuntimeContext runtimeContext = runtimeContext(executionContext);
        Map<String, List<io.agentscope.core.message.ToolResultBlock>> observations = new ConcurrentHashMap<>();
        Model meteredModel = new MeteredModel(modelProvider.apply(settings), settings, executionContext, observations);
        ReActAgent agent = ReActAgent.builder()
                .name(definition.name())
                .description(definition.description())
                .sysPrompt(definition.systemPrompt())
                .model(meteredModel)
                .toolkit(definition.toolkit())
                .maxIters(definition.maxIterations())
                .generateOptions(generateOptions(settings))
                .build();
        executionContext.cancellationToken().onCancel(() -> agent.interrupt(runtimeContext));
        try {
            Msg result = agent.call(prompt == null ? "" : prompt, runtimeContext).block();
            if (result == null) throw new IllegalStateException(definition.name() + " returned no result");
            Map<String, List<io.agentscope.core.message.ToolResultBlock>> snapshot = new java.util.HashMap<>();
            observations.forEach((name, results) -> snapshot.put(name, List.copyOf(results)));
            return new AgentScopeInvocation(result, Map.copyOf(snapshot));
        } finally {
            agent.close();
        }
    }

    private RuntimeContext runtimeContext(NodeExecutionContext context) {
        var request = context.request();
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .put(NodeExecutionContext.class, context);
        if (request != null) {
            builder.sessionId(request.sessionId()).userId(String.valueOf(request.userId()));
        }
        return builder.build();
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
        private final java.util.Set<String> observedIds = ConcurrentHashMap.newKeySet();

        private MeteredModel(Model delegate, LlmSettingsDTO settings, NodeExecutionContext context,
                             Map<String, List<io.agentscope.core.message.ToolResultBlock>> observations) {
            this.delegate = Objects.requireNonNull(delegate);
            this.settings = Objects.requireNonNull(settings);
            this.context = context;
            this.observations = observations;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            messages.stream().flatMap(message -> message
                    .getContentBlocks(io.agentscope.core.message.ToolResultBlock.class).stream())
                    .filter(result -> observedIds.add(result.getId()))
                    .forEach(result -> observations.computeIfAbsent(result.getName(), ignored -> new CopyOnWriteArrayList<>())
                            .add(result));
            long startedAt = System.nanoTime();
            return delegate.stream(messages, tools, options).doOnNext(response -> {
                var usage = response.getUsage();
                var request = context.request();
                if (usage == null || usage.getTotalTokens() <= 0 || request == null || request.usageConsumer() == null) return;
                request.usageConsumer().accept(LlmResponse.builder()
                        .provider(settings.getProvider())
                        .model(delegate.getModelName())
                        .promptTokens(usage.getInputTokens())
                        .completionTokens(usage.getOutputTokens())
                        .totalTokens(usage.getTotalTokens())
                        .latencyMs((System.nanoTime() - startedAt) / 1_000_000)
                        .build());
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
