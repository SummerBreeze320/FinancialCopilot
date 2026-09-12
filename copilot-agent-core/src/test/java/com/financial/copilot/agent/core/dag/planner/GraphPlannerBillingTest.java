package com.financial.copilot.agent.core.dag.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.dag.planner.tool.*;
import com.financial.copilot.agent.core.llm.dto.LlmRequest;
import com.financial.copilot.agent.core.llm.dto.LlmResponse;
import com.financial.copilot.agent.core.llm.service.LlmService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphPlannerBillingTest {
    @Test
    void everyPlannerModelCallForwardsUsageConsumer() {
        LlmService model = Mockito.mock(LlmService.class);
        Mockito.when(model.chat(Mockito.any(LlmRequest.class))).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            request.getUsageConsumer().accept(LlmResponse.builder().totalTokens(1).build());
            return "invalid";
        });
        List<LlmResponse> usages = new ArrayList<>();
        GraphPlanner planner = new GraphPlanner(new MetricRAGTool(), new SkillRegistryTool(),
                new CapabilityRegistryTool(), new MarketMemoryTool(), model, new ObjectMapper());

        planner.plan(new GraphPlanningRequest("分析基金", "s", null, usages::add, false));

        Mockito.verify(model, Mockito.times(4)).chat(Mockito.any(LlmRequest.class));
        assertThat(usages).hasSize(4);
    }
}
