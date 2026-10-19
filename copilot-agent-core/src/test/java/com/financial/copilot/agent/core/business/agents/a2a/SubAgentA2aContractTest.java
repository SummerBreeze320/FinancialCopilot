package com.financial.copilot.agent.core.business.agents.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.business.agents.ReportSynthesizerAgent;
import com.financial.copilot.agent.core.business.agents.fund.FundAnalyzerAgent;
import com.financial.copilot.agent.core.business.agents.fund.FundComparatorAgent;
import com.financial.copilot.agent.core.business.agents.fund.FundScreenerAgent;
import com.financial.copilot.agent.core.business.agents.stock.StockAnalyzerAgent;
import com.financial.copilot.agent.core.business.agents.stock.StockComparatorAgent;
import com.financial.copilot.agent.core.business.agents.stock.StockScreenerAgent;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.agentscope.RoleToolkitRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("SubAgents A2A 契约测试")
class SubAgentA2aContractTest {

    @Test
    @DisplayName("验证所有 SubAgents 均实现 A2aExecutableAgent 且正确提供 AgentCard 与支持度匹配")
    void testAllSubAgentsImplementA2aExecutableAgent() {
        AgentScopeAgentFactory factory = mock(AgentScopeAgentFactory.class);
        RoleToolkitRegistry registry = mock(RoleToolkitRegistry.class);
        ObjectMapper mapper = new ObjectMapper();

        FundScreenerAgent fundScreener = new FundScreenerAgent(factory, registry, mapper);
        assertInstanceOf(A2aExecutableAgent.class, fundScreener);
        assertEquals("FundScreenerAgent", ((A2aExecutableAgent) fundScreener).agentCard().name());
        assertTrue(((A2aExecutableAgent) fundScreener).supports("SCREENING", "FUND"));
        assertFalse(((A2aExecutableAgent) fundScreener).supports("SCREENING", "STOCK"));

        StockScreenerAgent stockScreener = new StockScreenerAgent(factory, registry);
        assertInstanceOf(A2aExecutableAgent.class, stockScreener);
        assertTrue(((A2aExecutableAgent) stockScreener).supports("SCREENING", "STOCK"));

        FundAnalyzerAgent fundAnalyzer = new FundAnalyzerAgent(factory, registry, mapper);
        assertInstanceOf(A2aExecutableAgent.class, fundAnalyzer);
        assertTrue(((A2aExecutableAgent) fundAnalyzer).supports("BATCH_ANALYSIS", "FUND"));

        StockAnalyzerAgent stockAnalyzer = new StockAnalyzerAgent(factory, registry);
        assertInstanceOf(A2aExecutableAgent.class, stockAnalyzer);
        assertTrue(((A2aExecutableAgent) stockAnalyzer).supports("BATCH_ANALYSIS", "STOCK"));

        FundComparatorAgent fundComparator = new FundComparatorAgent(factory, registry, mapper);
        assertInstanceOf(A2aExecutableAgent.class, fundComparator);
        assertTrue(((A2aExecutableAgent) fundComparator).supports("COMPARISON", "FUND"));
        assertTrue(((A2aExecutableAgent) fundComparator).supports("DEEP_DIVE", "FUND"));

        StockComparatorAgent stockComparator = new StockComparatorAgent(factory, registry);
        assertInstanceOf(A2aExecutableAgent.class, stockComparator);
        assertTrue(((A2aExecutableAgent) stockComparator).supports("COMPARISON", "STOCK"));
        assertTrue(((A2aExecutableAgent) stockComparator).supports("DEEP_DIVE", "STOCK"));

        ReportSynthesizerAgent synth = new ReportSynthesizerAgent(factory, registry);
        assertInstanceOf(A2aExecutableAgent.class, synth);
        assertTrue(((A2aExecutableAgent) synth).supports("SYNTHESIS", "FUND"));
        assertTrue(((A2aExecutableAgent) synth).supports("SYNTHESIS", "STOCK"));
    }
}
