package com.financial.copilot.agent.core.dag.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.model.*;
import com.financial.copilot.agent.core.dag.planner.tool.*;
import com.financial.copilot.agent.core.llm.dto.LlmSettingsDTO;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GraphPlannerAgentTest {
    @Test
    void plannerUsesDiscoveryToolThenFinishesGraphThroughAgentScope() throws Exception {
        ExecutionGraph graph = new ExecutionGraph("g-native");
        graph.addNode(GraphNode.builder().nodeId("screen").taskType("SCREENING").outputType(ArtifactType.FUND_POOL).build());
        String graphJson = new ObjectMapper().writeValueAsString(new GraphPlan(ExecutionGraphSnapshot.from(graph)));
        AtomicInteger turns = new AtomicInteger();
        Model model = new Model() {
            @Override public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options){
                int turn=turns.incrementAndGet();
                ContentBlock block=turn==1
                        ? ToolUseBlock.builder().id("m1").name("search_metrics").input(Map.of("query","医药基金"))
                        .content("{\"query\":\"医药基金\"}").build()
                        : TextBlock.builder().text(graphJson).build();
                return Flux.just(ChatResponse.builder().id("r"+turn).content(List.of(block))
                        .finishReason(turn<2?"tool_calls":"stop").build());
            }
            @Override public String getModelName(){return "scripted";}
        };
        AgentScopeAgentFactory factory=new AgentScopeAgentFactory(()->LlmSettingsDTO.builder().model("scripted").build(),ignored->model);
        GraphPlannerAgent planner=new GraphPlannerAgent(factory,new MetricRAGTool(),new SkillRegistryTool(),
                new CapabilityRegistryTool(),new MarketMemoryTool(),new FinancialDocumentSearchTool(),new ObjectMapper());

        ExecutionGraph planned=planner.plan(new GraphPlanningRequest("医药基金","session",null,ignored->{},false));

        assertThat(turns).hasValue(2);
        assertThat(planned.getGraphId()).isEqualTo("g-native");
        assertThat(planned.getNodes()).containsKey("screen");
    }
}
