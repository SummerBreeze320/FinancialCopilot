package com.financial.copilot.agent.core.agents;

import com.financial.copilot.agent.core.agents.stock.StockComparatorAgent;
import com.financial.copilot.agent.core.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.dag.runtime.RunMode;
import com.financial.copilot.agent.core.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.llm.dto.LlmSettingsDTO;
import com.financial.copilot.agent.tools.stock.StockQuantAnalysisTool;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NativeStockComparatorAgentTest {

    @Test
    void comparesBothStocksThroughAgentScopeToolCall() {
        AtomicInteger turns = new AtomicInteger();
        Model model = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (turns.incrementAndGet() == 1) {
                    return Flux.just(response(ToolUseBlock.builder().id("compare-1").name("compare_stock_metrics")
                            .input(Map.of("code_a", "600519.SH", "code_b", "000858.SZ"))
                            .content("{\"code_a\":\"600519.SH\",\"code_b\":\"000858.SZ\"}").build(), "tool_calls"));
                }
                ToolResultBlock result = messages.stream().filter(msg -> msg.hasContentBlocks(ToolResultBlock.class))
                        .findFirst().orElseThrow().getFirstContentBlock(ToolResultBlock.class);
                assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
                assertThat(result.getOutput().toString()).contains("pe", "25", "18");
                return Flux.just(response(TextBlock.builder().text("# 股票对标\n茅台与五粮液的工具事实对比").build(), "stop"));
            }

            @Override
            public String getModelName() {
                return "scripted";
            }
        };
        StockQuantAnalysisTool tool = mock(StockQuantAnalysisTool.class);
        when(tool.getStockMetrics("600519.SH")).thenReturn("{\"pe\":25}");
        when(tool.getStockMetrics("000858.SZ")).thenReturn("{\"pe\":18}");
        AgentScopeAgentFactory factory = new AgentScopeAgentFactory(
                () -> LlmSettingsDTO.builder().model("scripted").build(), ignored -> model);
        StockComparatorAgent agent = new StockComparatorAgent(factory, tool);
        GraphRunRequest request = new GraphRunRequest("run", 1L,java.util.UUID.randomUUID(), null,  "session", "比较两只股票", false,
                null, ignored -> {}, RunMode.SYNC);
        GraphNode node = GraphNode.builder().nodeId("compare").taskType("COMPARISON")
                .outputType(ArtifactType.GENERAL)
                .param("targetCodes", List.of("600519.SH", "000858.SZ")).build();

        var artifact = agent.execute(node, NodeInput.empty(),
                new NodeExecutionContext(request,"test-node",  new ArtifactStore(), new CancellationToken("compare")));

        assertThat(turns).hasValue(2);
        assertThat(artifact.payload()).contains("股票对标");
    }

    private static ChatResponse response(ContentBlock block, String finishReason) {
        return ChatResponse.builder().id("reply").content(List.of(block)).finishReason(finishReason).build();
    }
}
