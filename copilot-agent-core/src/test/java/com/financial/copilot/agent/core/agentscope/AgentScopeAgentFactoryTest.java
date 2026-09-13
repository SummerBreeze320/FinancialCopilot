package com.financial.copilot.agent.core.agentscope;

import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.dag.runtime.RunMode;
import com.financial.copilot.agent.core.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.llm.dto.LlmResponse;
import com.financial.copilot.agent.core.llm.dto.LlmSettingsDTO;
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

class AgentScopeAgentFactoryTest {

    @Test
    void nativeReactAgentCallsToolThenUsesObservationAndMetersEveryModelTurn() {
        ScriptedModel model = new ScriptedModel();
        AgentScopeAgentFactory factory = new AgentScopeAgentFactory(
                () -> LlmSettingsDTO.builder().model("scripted").build(), ignored -> model);
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
}
