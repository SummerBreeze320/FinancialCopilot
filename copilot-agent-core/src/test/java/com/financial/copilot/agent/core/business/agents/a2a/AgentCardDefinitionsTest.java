package com.financial.copilot.agent.core.business.agents.a2a;

import io.a2a.spec.AgentCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("A2A AgentCardDefinitions 元数据规范测试")
class AgentCardDefinitionsTest {

    @Test
    @DisplayName("验证所有领域智能体的 AgentCard 符合 A2A 规范且包含必要标签")
    void testAllAgentCardsValid() {
        AgentCard fundScreener = AgentCardDefinitions.fundScreenerCard();
        assertNotNull(fundScreener);
        assertEquals("FundScreenerAgent", fundScreener.name());
        assertFalse(fundScreener.skills().isEmpty());
        assertTrue(fundScreener.skills().getFirst().tags().contains("SCREENING"));
        assertTrue(fundScreener.skills().getFirst().tags().contains("FUND"));

        AgentCard stockAnalyzer = AgentCardDefinitions.stockAnalyzerCard();
        assertNotNull(stockAnalyzer);
        assertEquals("StockAnalyzerAgent", stockAnalyzer.name());
        assertTrue(stockAnalyzer.skills().getFirst().tags().contains("BATCH_ANALYSIS"));
        assertTrue(stockAnalyzer.skills().getFirst().tags().contains("STOCK"));

        AgentCard synth = AgentCardDefinitions.reportSynthesizerCard();
        assertNotNull(synth);
        assertEquals("ReportSynthesizerAgent", synth.name());
        assertTrue(synth.skills().getFirst().tags().contains("SYNTHESIS"));
        assertTrue(synth.skills().getFirst().tags().contains("ALL"));
    }
}
