package com.financial.copilot.agent.core.dag.planner.tool;

import com.financial.copilot.agent.core.memory.LongTermMemoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>市场与长期记忆检索工具单元测试</h1>
 *
 * @author FinancialCopilot
 */
class MarketMemoryToolTest {

    @Test
    @DisplayName("测试空会话或未注入服务时的空结果安全兜底")
    void testRetrieveMemoryWithNullServiceOrSession() {
        MarketMemoryTool tool = new MarketMemoryTool(null);
        MarketMemoryTool.MemoryRetrievalResult result = tool.retrieveMemory(null, "选医药基金", 5);

        assertNotNull(result);
        assertThat(result.relevantFacts()).isEmpty();
        assertThat(result.historicalDecisions()).isEmpty();

        MarketMemoryTool.MemoryRetrievalResult resultBlankSession = tool.retrieveMemory("", "选医药基金", 5);
        assertThat(resultBlankSession.relevantFacts()).isEmpty();
    }

    @Test
    @DisplayName("测试注入 LongTermMemoryService 时成功召回事实与历史决策")
    void testRetrieveMemoryWithMockedService() {
        LongTermMemoryService memoryService = Mockito.mock(LongTermMemoryService.class);
        Mockito.when(memoryService.retrieveRelevantFacts("session-100", "稳健理财", 3))
                .thenReturn(List.of("用户风险偏好为稳健型R2", "偏好近三年最大回撤<15%"));
        Mockito.when(memoryService.retrieve("session-100", 3))
                .thenReturn(List.of("上周生成易方达蓝筹研报"));

        MarketMemoryTool tool = new MarketMemoryTool(memoryService);
        MarketMemoryTool.MemoryRetrievalResult result = tool.retrieveMemory("session-100", "稳健理财", 3);

        assertNotNull(result);
        assertEquals("session-100", result.sessionId());
        assertThat(result.relevantFacts()).hasSize(2)
                .contains("用户风险偏好为稳健型R2", "偏好近三年最大回撤<15%");
        assertThat(result.historicalDecisions()).hasSize(1)
                .contains("上周生成易方达蓝筹研报");
    }
}
