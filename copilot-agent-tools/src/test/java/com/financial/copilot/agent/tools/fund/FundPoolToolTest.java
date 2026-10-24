package com.financial.copilot.agent.tools.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.business.fund.entity.FundPoolItem;
import com.financial.copilot.domain.business.fund.port.FundPoolPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("FundPoolTool 基金池管理工具测试")
class FundPoolToolTest {

    private FundPoolPort port;
    private FundPoolTool tool;

    @BeforeEach
    void setUp() {
        port = mock(FundPoolPort.class);
        tool = new FundPoolTool(port, new ObjectMapper());
    }

    @Test
    @DisplayName("添加基金到核心池成功返回 JSON")
    void testAddFundSuccess() {
        when(port.addOrUpdate(any())).thenAnswer(inv -> inv.getArgument(0));

        String result = tool.addFund(1L, "005827", "易方达蓝筹", "CORE", 0.2, "看好消费反弹", "蓝筹");
        assertTrue(result.contains("SUCCESS"));
        assertTrue(result.contains("005827"));
        assertTrue(result.contains("CORE"));
    }

    @Test
    @DisplayName("更新基金池仓位与理由成功")
    void testUpdateFund() {
        when(port.addOrUpdate(any())).thenAnswer(inv -> inv.getArgument(0));

        String result = tool.updateFund(1L, "005827", "CORE", 0.25, "加仓5%");
        assertTrue(result.contains("SUCCESS"));
        assertTrue(result.contains("0.25"));
    }

    @Test
    @DisplayName("移出基金池成功")
    void testRemoveFund() {
        when(port.remove(1L, "005827", "CORE")).thenReturn(true);

        String result = tool.removeFund(1L, "005827", "CORE");
        assertTrue(result.contains("SUCCESS"));
        assertTrue(result.contains("已移出"));
    }

    @Test
    @DisplayName("清空基金池成功")
    void testClearPool() {
        when(port.clear(1L, "WATCH")).thenReturn(3);

        String result = tool.clearPool(1L, "WATCH");
        assertTrue(result.contains("SUCCESS"));
        assertTrue(result.contains("3"));
    }

    @Test
    @DisplayName("查询基金池列表")
    void testQueryPool() {
        FundPoolItem item = FundPoolItem.builder()
                .userId(1L)
                .fundCode("005827")
                .fundName("易方达蓝筹")
                .poolType("CORE")
                .targetWeight(new BigDecimal("0.2"))
                .build();
        when(port.listByUser(1L, "CORE")).thenReturn(List.of(item));

        String result = tool.queryPool(1L, "CORE");
        assertTrue(result.contains("005827"));
        assertTrue(result.contains("易方达蓝筹"));
    }
}
