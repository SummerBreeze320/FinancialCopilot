package com.financial.copilot.agent.core.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.agents.fund.FundScreenerAgent;
import com.financial.copilot.agent.core.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.artifact.payload.FundPool;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.*;
import com.financial.copilot.agent.core.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.llm.dto.LlmSettingsDTO;
import com.financial.copilot.agent.tools.fund.FundScreeningTool;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NativeFundScreenerAgentTest {

    @Test
    void screeningIsChosenAndExecutedByAgentScopeReact() throws Exception {
        AtomicInteger turns = new AtomicInteger();
        Model model = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (turns.incrementAndGet() == 1) {
                    return Flux.just(response(ToolUseBlock.builder().id("screen-1").name("screen_funds")
                            .input(Map.of("fundType", "偏股混合型", "sectorTheme", "医药", "limit", 5))
                            .content("{\"fundType\":\"偏股混合型\",\"sectorTheme\":\"医药\",\"limit\":5}")
                            .build(), "tool_calls"));
                }
                ToolResultBlock result = messages.stream().filter(msg -> msg.hasContentBlocks(ToolResultBlock.class))
                        .findFirst().orElseThrow().getFirstContentBlock(ToolResultBlock.class);
                assertThat(result.getState()).as(result.toString()).isEqualTo(ToolResultState.SUCCESS);
                assertThat(result.getOutput().toString()).doesNotContain("Error");
                return Flux.just(response(TextBlock.builder().text("已根据工具事实完成筛选").build(), "stop"));
            }

            @Override public String getModelName() { return "scripted"; }
        };
        FundScreeningTool tool = mock(FundScreeningTool.class);
        when(tool.screenFunds(any())).thenReturn("[{\"fundCode\":\"003095\",\"fundName\":\"中欧医疗健康\"}]");
        AgentScopeAgentFactory factory = new AgentScopeAgentFactory(
                () -> LlmSettingsDTO.builder().model("scripted").build(), ignored -> model);
        FundScreenerAgent agent = new FundScreenerAgent(factory, tool, new ObjectMapper());
        GraphRunRequest request = new GraphRunRequest("run", 1L, "session", "筛选医药基金", false,
                null, ignored -> {}, RunMode.SYNC);

        var artifact = agent.execute(GraphNode.builder().nodeId("screen").taskType("SCREENING")
                        .outputType(ArtifactType.FUND_POOL).build(), NodeInput.empty(),
                new NodeExecutionContext(request, new ArtifactStore(), new CancellationToken("screen")));

        assertThat(turns).hasValue(2);
        assertThat(artifact.payload()).isInstanceOf(FundPool.class);
        assertThat(((FundPool) artifact.payload()).fundCodes()).containsExactly("003095");
    }

    private static ChatResponse response(ContentBlock block, String finishReason) {
        return ChatResponse.builder().id("reply").content(List.of(block)).finishReason(finishReason).build();
    }
}
