package com.financial.copilot.agent.core.business.agents.a2a;

import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import io.a2a.spec.AgentCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("A2aAgentRegistry 注册中心测试")
class A2aAgentRegistryTest {

    static class MockAgent implements A2aExecutableAgent {
        private final AgentCard card;

        MockAgent(AgentCard card) {
            this.card = card;
        }

        @Override
        public AgentCard agentCard() {
            return card;
        }

        @Override
        public Artifact<?> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
            return null;
        }
    }

    @Test
    @DisplayName("验证按任务类型与资产类别匹配已注册智能体")
    void testFindAndGetAgent() {
        MockAgent fundScreener = new MockAgent(AgentCardDefinitions.fundScreenerCard());
        MockAgent stockScreener = new MockAgent(AgentCardDefinitions.stockScreenerCard());
        MockAgent synth = new MockAgent(AgentCardDefinitions.reportSynthesizerCard());

        A2aAgentRegistry registry = new A2aAgentRegistry(List.of(fundScreener, stockScreener, synth));

        Optional<A2aExecutableAgent> foundFund = registry.findAgent("SCREENING", "FUND");
        assertTrue(foundFund.isPresent());
        assertEquals("FundScreenerAgent", foundFund.get().agentCard().name());

        Optional<A2aExecutableAgent> foundStock = registry.findAgent("SCREENING", "STOCK");
        assertTrue(foundStock.isPresent());
        assertEquals("StockScreenerAgent", foundStock.get().agentCard().name());

        // SYNTHESIS matches ALL asset categories
        Optional<A2aExecutableAgent> foundSynth = registry.findAgent("SYNTHESIS", "STOCK");
        assertTrue(foundSynth.isPresent());
        assertEquals("ReportSynthesizerAgent", foundSynth.get().agentCard().name());

        // Missing task type throws exception on getRequiredAgent
        assertThrows(IllegalArgumentException.class, () -> registry.getRequiredAgent("NON_EXISTENT", "FUND"));
    }
}
