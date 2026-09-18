package com.financial.copilot.agent.core.infra.dag.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.model.*;
import com.financial.copilot.agent.core.infra.dag.planner.tool.*;
import com.financial.copilot.agent.core.infra.llm.dto.LlmSettingsDTO;
import com.financial.copilot.agent.core.infra.memory.MemoryClient;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
        AgentScopeAgentFactory factory=new AgentScopeAgentFactory(()->LlmSettingsDTO.builder().model("scripted").build(),ignored->model,mock(MemoryClient.class));
        GraphPlannerAgent planner=new GraphPlannerAgent(factory,new MetricRAGTool(),new SkillRegistryTool(),
                new CapabilityRegistryTool(),new MarketMemoryTool(),new FinancialDocumentSearchTool(),new ObjectMapper());

        ExecutionGraph planned=planner.plan(new GraphPlanningRequest("医药基金","session",null,ignored->{},false));

        assertThat(turns).hasValue(2);
        assertThat(planned.getGraphId()).isEqualTo("g-native");
        assertThat(planned.getNodes()).containsKey("screen");
    }

    @Test
    void plannerRejectsTaskTypesWithoutAnExecutableAgentScopeRole() throws Exception {
        ExecutionGraph graph = new ExecutionGraph("g-unsupported");
        graph.addNode(GraphNode.builder().nodeId("macro").taskType("MACRO")
                .outputType(ArtifactType.MACRO_FACTS).build());
        String graphJson = new ObjectMapper().writeValueAsString(new GraphPlan(ExecutionGraphSnapshot.from(graph)));
        AtomicInteger turns = new AtomicInteger();
        Model model = new Model() {
            @Override public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options){
                int turn=turns.incrementAndGet();
                ContentBlock block=turn==1
                        ? ToolUseBlock.builder().id("s1").name("list_skills").input(Map.of())
                        .content("{}").build()
                        : TextBlock.builder().text(graphJson).build();
                return Flux.just(ChatResponse.builder().id("r"+turn).content(List.of(block))
                        .finishReason(turn<2?"tool_calls":"stop").build());
            }
            @Override public String getModelName(){return "scripted";}
        };
        AgentScopeAgentFactory factory=new AgentScopeAgentFactory(()->LlmSettingsDTO.builder().model("scripted").build(),ignored->model,mock(MemoryClient.class));
        GraphPlannerAgent planner=new GraphPlannerAgent(factory,new MetricRAGTool(),new SkillRegistryTool(),
                new CapabilityRegistryTool(),new MarketMemoryTool(),new FinancialDocumentSearchTool(),new ObjectMapper());

        assertThatThrownBy(() -> planner.plan(new GraphPlanningRequest("分析宏观环境","session",null,ignored->{},false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid GraphPlan")
                .hasRootCauseMessage("No AgentScope role for task type: MACRO");
    }
}
