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
        assertEquals("session-100", result.sessionKey());
        assertThat(result.relevantFacts()).hasSize(2)
                .contains("用户风险偏好为稳健型R2", "偏好近三年最大回撤<15%");
        assertThat(result.historicalDecisions()).hasSize(1)
                .contains("上周生成易方达蓝筹研报");
    }

    @Test
    @DisplayName("测试启用远程记忆客户端时优先使用远程召回结果")
    void testRetrieveMemoryWithRemoteClient() {
        com.financial.copilot.agent.core.memory.remote.RemoteMemoryServiceClient remoteClient =
                Mockito.mock(com.financial.copilot.agent.core.memory.remote.RemoteMemoryServiceClient.class);
        Mockito.when(remoteClient.isEnabled()).thenReturn(true);

        com.financial.copilot.agent.core.memory.remote.dto.MemoryBundleDTO bundle =
                new com.financial.copilot.agent.core.memory.remote.dto.MemoryBundleDTO(
                        "用户偏好新能源",
                        List.of(new com.financial.copilot.agent.core.memory.remote.dto.MemoryBundleDTO.RecalledItemDTO(
                                "m1", 1, "重仓新能源与光伏", "偏好新能源", 10)),
                        10, 500
                );
        Mockito.when(remoteClient.recall(Mockito.any())).thenReturn(java.util.Optional.of(bundle));

        MarketMemoryTool tool = new MarketMemoryTool(null, remoteClient);
        MarketMemoryTool.MemoryRetrievalResult result = tool.retrieveMemory("u123:session-1", "新能源", 5);

        assertNotNull(result);
        assertThat(result.relevantFacts()).contains("重仓新能源与光伏");
        assertThat(result.historicalDecisions()).contains("用户偏好新能源");
    }

    @Test
    @DisplayName("测试远程记忆客户端未启用或超时时平滑降级至本地记忆库")
    void testRetrieveMemoryFallbackToLocalWhenRemoteFails() {
        LongTermMemoryService memoryService = Mockito.mock(LongTermMemoryService.class);
        Mockito.when(memoryService.retrieveRelevantFacts("session-fallback", "芯片", 5))
                .thenReturn(List.of("关注半导体国产化"));
        Mockito.when(memoryService.retrieve("session-fallback", 5))
                .thenReturn(List.of());

        com.financial.copilot.agent.core.memory.remote.RemoteMemoryServiceClient remoteClient =
                Mockito.mock(com.financial.copilot.agent.core.memory.remote.RemoteMemoryServiceClient.class);
        Mockito.when(remoteClient.isEnabled()).thenReturn(true);
        Mockito.when(remoteClient.recall(Mockito.any())).thenReturn(java.util.Optional.empty()); // 模拟超时降级

        MarketMemoryTool tool = new MarketMemoryTool(memoryService, remoteClient);
        MarketMemoryTool.MemoryRetrievalResult result = tool.retrieveMemory("session-fallback", "芯片", 5);

        assertNotNull(result);
        assertThat(result.relevantFacts()).contains("关注半导体国产化");
    }
}
