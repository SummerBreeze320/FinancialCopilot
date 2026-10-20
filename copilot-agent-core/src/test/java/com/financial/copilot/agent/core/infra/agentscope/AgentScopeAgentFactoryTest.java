package com.financial.copilot.agent.core.infra.agentscope;

import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.RunMode;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.infra.llm.dto.LlmResponse;
import com.financial.copilot.agent.core.infra.llm.dto.LlmSettingsDTO;
import com.financial.copilot.agent.core.infra.memory.MemoryClient;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolParam;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentScopeAgentFactoryTest {

    @Test
    void nativeReactAgentCallsToolThenUsesObservationAndMetersEveryModelTurn() {
        ScriptedModel model = new ScriptedModel();
        AgentScopeAgentFactory factory = new AgentScopeAgentFactory(
                () -> LlmSettingsDTO.builder().model("scripted").build(), ignored -> model, mock(MemoryClient.class));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new EchoTool());
        AtomicInteger meteredTokens = new AtomicInteger();
        AtomicReference<LlmResponse> lastUsage = new AtomicReference<>();
        GraphRunRequest request = new GraphRunRequest("run-1", 7L,java.util.UUID.randomUUID(), null,  "session-1", "echo hello", false,
                null, usage -> {
                    meteredTokens.addAndGet(usage.getTotalTokens());
                    lastUsage.set(usage);
                }, RunMode.SYNC);
        NodeExecutionContext context = new NodeExecutionContext(request,"test-node",  new ArtifactStore(), new CancellationToken("node-1"));

        Msg result = factory.invoke(new AgentScopeAgentFactory.AgentDefinition(
                "EchoAgent", "test agent", "Call echo and use its result.", toolkit, 3), "hello", context);

        assertThat(result.getTextContent()).isEqualTo("observed:HELLO");
        assertThat(model.calls).hasValue(2);
        assertThat(model.sawToolResult).isTrue();
        assertThat(meteredTokens).hasValue(10);
        assertThat(lastUsage.get().getModel()).isEqualTo("scripted");
    }

    @Test
    void runtimeContextCarriesDistinctAgentAndWorkflowRunIds() {
        AgentScopeAgentFactory factory = new AgentScopeAgentFactory(
                () -> LlmSettingsDTO.builder().model("scripted").build(),
                ignored -> new ScriptedModel(),
                mock(MemoryClient.class));
        NodeExecutionContext context = nodeContext("wf-1", "analysis", 2);
        io.agentscope.core.agent.RuntimeContext runtimeContext = factory.runtimeContext(context);
        assertThat(runtimeContext.<String>get("runId")).isEqualTo("wf-1:analysis:2");
        assertThat(runtimeContext.<String>get("workflowRunId")).isEqualTo("wf-1");
        assertThat(runtimeContext.get(NodeExecutionContext.class).nodeId()).isEqualTo("analysis");
        assertThat(runtimeContext.get(AgentInvocationIdentity.class).agentRunId()).isEqualTo("wf-1:analysis:2");
        assertThat(runtimeContext.get(AgentInvocationIdentity.class).attempt()).isEqualTo(2);
    }

    private NodeExecutionContext nodeContext(String workflowRunId, String nodeId, int attempt) {
        com.financial.copilot.agent.core.infra.context.ExecutionIdentity identity =
                new com.financial.copilot.agent.core.infra.context.ExecutionIdentity("DEFAULT", 100L, java.util.UUID.randomUUID(), workflowRunId, "session-1");
        GraphRunRequest request = new GraphRunRequest(
                identity,
                new com.financial.copilot.agent.core.infra.context.ConversationContextSnapshot(List.of(), null, "prompt", java.time.Instant.now()),
                null,
                false,
                usage -> {},
                RunMode.SYNC
        );
        return new NodeExecutionContext(request, nodeId, new ArtifactStore(), new CancellationToken(nodeId), attempt);
    }

    @Test
    void loopsWithConsecutiveDuplicateCallsAreInterceptedAndDegraded() {
        Model loopingModel = new Model() {
            private final AtomicInteger calls = new AtomicInteger();
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                calls.incrementAndGet();
                return Flux.just(ChatResponse.builder()
                        .id("loop-reply")
                        .content(List.of(ToolUseBlock.builder().id("tool-loop").name("echo")
                                .input(Map.of("value", "loop")).content("{\"value\":\"loop\"}").build()))
                        .finishReason("tool_calls")
                        .build());
            }
            @Override
            public String getModelName() { return "looping"; }
        };

        AgentScopeAgentFactory factory = new AgentScopeAgentFactory(
                () -> LlmSettingsDTO.builder().model("looping").build(), ignored -> loopingModel, mock(MemoryClient.class));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new EchoTool());
        NodeExecutionContext context = nodeContext("wf-loop", "node-loop", 1);

        AgentScopeInvocation invocation = factory.invokeWithTrace(
                new AgentScopeAgentFactory.AgentDefinition("LoopAgent", "test", "prompt", toolkit, 5),
                "trigger",
                context
        );

        assertThat(invocation.degraded()).isTrue();
        assertThat(invocation.terminationReason()).isEqualTo("CONSECUTIVE_DUPLICATE_CALL");
        assertThat(invocation.reply().getTextContent()).contains("检测到以完全相同的入参连续重复调用工具 [echo]");
    }

    static final class EchoTool {
        @Tool(name = "echo", description = "Uppercase input", readOnly = true)
        public String echo(@ToolParam(name = "value", description = "text") String value) {
            return value.toUpperCase();
        }
    }

    static final class ScriptedModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();
        private boolean sawToolResult;

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int call = calls.incrementAndGet();
            ChatUsage usage = new ChatUsage(3, 2, 0);
            if (call == 1) {
                assertThat(tools).extracting(ToolSchema::getName).contains("echo");
                return Flux.just(ChatResponse.builder()
                        .id("reply-1")
                        .content(List.of(ToolUseBlock.builder().id("tool-1").name("echo")
                                .input(Map.of("value", "hello")).content("{\"value\":\"hello\"}").build()))
                        .usage(usage)
                        .finishReason("tool_calls")
                        .build());
            }
            ToolResultBlock toolResult = messages.stream().filter(message -> message.hasContentBlocks(ToolResultBlock.class))
                    .findFirst().orElseThrow().getFirstContentBlock(ToolResultBlock.class);
            sawToolResult = toolResult.getState() == ToolResultState.SUCCESS
                    && toolResult.getOutput().toString().contains("HELLO");
            return Flux.just(ChatResponse.builder()
                    .id("reply-2")
                    .content(List.of(TextBlock.builder().text("observed:HELLO").build()))
                    .usage(usage)
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "scripted";
        }
    }

    @Test
    void shouldWireLlmGatewayClientSuccessfully() {
        com.financial.copilot.gateway.client.LlmGatewayClient mockGatewayClient = org.mockito.Mockito.mock(com.financial.copilot.gateway.client.LlmGatewayClient.class);
        com.financial.copilot.agent.core.infra.llm.config.LlmConfigManager configManager = org.mockito.Mockito.mock(com.financial.copilot.agent.core.infra.llm.config.LlmConfigManager.class);
        org.mockito.Mockito.when(configManager.getActiveSettings()).thenReturn(LlmSettingsDTO.builder().model("test-model").build());

        AgentScopeAgentFactory factory = new AgentScopeAgentFactory(
                configManager,
                mock(MemoryClient.class),
                null,
                mockGatewayClient
        );
        assertThat(factory).isNotNull();
    }
}
