package com.financial.copilot.agent.core.infra.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.financial.copilot.agent.core.infra.coordination.AgentOperationLedger;
import com.financial.copilot.agent.core.infra.coordination.EvidenceMergeCoordinator;
import com.financial.copilot.agent.core.infra.coordination.InMemoryAgentOperationLedger;
import com.financial.copilot.agent.core.infra.evidence.RunEvidenceStore;
import com.financial.copilot.agent.core.infra.guardrail.ActionGuardrail;
import com.financial.copilot.agent.core.infra.prompt.SubagentPrompts;
import com.financial.copilot.agent.core.infra.tool.ResearchEvidenceTools;
import com.financial.copilot.agent.core.infra.tool.fund.FundResearchTools;
import com.financial.copilot.agent.core.infra.tool.market.MarketResearchTools;
import com.financial.copilot.agent.core.infra.tool.stock.StockResearchTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubagentFactoryTest {

    private ChatModel chatModel;
    private FundResearchTools fundResearchTools;
    private StockResearchTools stockResearchTools;
    private MarketResearchTools marketResearchTools;
    private ResearchEvidenceTools evidenceTools;

    private SubagentFactory subagentFactory;
    private AgentOperationLedger ledger;
    private EvidenceMergeCoordinator coordinator;

    @BeforeEach
    void setUp() {
        chatModel = Mockito.mock(ChatModel.class);
        fundResearchTools = Mockito.mock(FundResearchTools.class);
        stockResearchTools = Mockito.mock(StockResearchTools.class);
        marketResearchTools = Mockito.mock(MarketResearchTools.class);
        evidenceTools = Mockito.mock(ResearchEvidenceTools.class);

        subagentFactory = new SubagentFactory(
                chatModel,
                fundResearchTools,
                stockResearchTools,
                marketResearchTools,
                evidenceTools
        );

        ledger = new InMemoryAgentOperationLedger();
        coordinator = new EvidenceMergeCoordinator(new RunEvidenceStore());
    }

    @Test
    void buildsAndCachesSubagentsForAllSubagentRoles() {
        List<AgentRole> roles = AgentRole.subagentRoles();
        assertThat(roles).containsExactly(
                AgentRole.FUND_RESEARCH,
                AgentRole.STOCK_RESEARCH,
                AgentRole.MARKET_RESEARCH,
                AgentRole.RISK_REVIEW,
                AgentRole.REPORT
        );

        for (AgentRole role : roles) {
            ReactAgent agent1 = subagentFactory.getSubagent(role);
            assertThat(agent1).isNotNull();

            // Verify singleton caching
            ReactAgent agent2 = subagentFactory.getSubagent(role);
            assertThat(agent2).isSameAs(agent1);
        }
    }

    @Test
    void rejectsSupervisorRoleWhenRequestingSubagent() {
        assertThatThrownBy(() -> subagentFactory.getSubagent(AgentRole.SUPERVISOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot create Subagent for non-subagent role");
    }

    @Test
    void createsAllSubagentToolCallbacksWithProperContextualWrapping() {
        ActionGuardrail guardrail = Mockito.mock(ActionGuardrail.class);

        List<ToolCallback> callbacks = subagentFactory.createAllSubagentToolCallbacks(ledger, coordinator, guardrail);
        assertThat(callbacks).hasSize(5);

        List<AgentRole> callbackRoles = callbacks.stream()
                .filter(c -> c instanceof ContextualAgentToolCallback)
                .map(c -> ((ContextualAgentToolCallback) c).getRole())
                .toList();

        assertThat(callbackRoles).containsExactly(
                AgentRole.FUND_RESEARCH,
                AgentRole.STOCK_RESEARCH,
                AgentRole.MARKET_RESEARCH,
                AgentRole.RISK_REVIEW,
                AgentRole.REPORT
        );
    }

    @Test
    void verifiesAgentRoleMetadataAndFromAgentName() {
        assertThat(AgentRole.fromAgentName("fund_research_agent")).isEqualTo(AgentRole.FUND_RESEARCH);
        assertThat(AgentRole.fromAgentName("STOCK_RESEARCH")).isEqualTo(AgentRole.STOCK_RESEARCH);
        assertThat(AgentRole.fromAgentName("research_supervisor")).isEqualTo(AgentRole.SUPERVISOR);

        assertThat(AgentRole.SUPERVISOR.isSubagent()).isFalse();
        assertThat(AgentRole.FUND_RESEARCH.isSubagent()).isTrue();

        assertThatThrownBy(() -> AgentRole.fromAgentName("non_existent_role"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifiesSubagentPromptsResolution() {
        assertThat(SubagentPrompts.getSystemPrompt(AgentRole.FUND_RESEARCH))
                .isEqualTo(SubagentPrompts.FUND_AGENT_SYSTEM_PROMPT);
        assertThat(SubagentPrompts.getSystemPrompt(AgentRole.STOCK_RESEARCH))
                .isEqualTo(SubagentPrompts.STOCK_AGENT_SYSTEM_PROMPT);
        assertThat(SubagentPrompts.getSystemPrompt(AgentRole.MARKET_RESEARCH))
                .isEqualTo(SubagentPrompts.MARKET_AGENT_SYSTEM_PROMPT);
        assertThat(SubagentPrompts.getSystemPrompt(AgentRole.RISK_REVIEW))
                .isEqualTo(SubagentPrompts.RISK_REVIEW_SYSTEM_PROMPT);
        assertThat(SubagentPrompts.getSystemPrompt(AgentRole.REPORT))
                .isEqualTo(SubagentPrompts.REPORT_AGENT_SYSTEM_PROMPT);

        assertThatThrownBy(() -> SubagentPrompts.getSystemPrompt(AgentRole.SUPERVISOR))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
