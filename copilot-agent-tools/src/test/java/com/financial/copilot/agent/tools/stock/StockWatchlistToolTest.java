package com.financial.copilot.agent.tools.stock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.business.stock.entity.StockWatchlistItem;
import com.financial.copilot.domain.business.stock.port.StockWatchlistPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("StockWatchlistTool 自选股管理工具测试")
class StockWatchlistToolTest {

    private StockWatchlistPort port;
    private StockWatchlistTool tool;

    @BeforeEach
    void setUp() {
        port = mock(StockWatchlistPort.class);
        tool = new StockWatchlistTool(port, new ObjectMapper());
    }

    @Test
    @DisplayName("添加股票到自选列表成功")
    void testAddStockSuccess() {
        when(port.addOrUpdate(any())).thenAnswer(inv -> inv.getArgument(0));

        String result = tool.addStock(1L, "600519", "贵州茅台", "白酒消费", 1800.0, "行业壁垒深厚", "HIGH");
        assertTrue(result.contains("SUCCESS"));
        assertTrue(result.contains("600519"));
        assertTrue(result.contains("贵州茅台"));
    }

    @Test
    @DisplayName("移除自选股成功")
    void testRemoveStock() {
        when(port.remove(1L, "600519", "白酒消费")).thenReturn(true);

        String result = tool.removeStock(1L, "600519", "白酒消费");
        assertTrue(result.contains("SUCCESS"));
        assertTrue(result.contains("已移出"));
    }

    @Test
    @DisplayName("清空自选股分组")
    void testClearWatchlist() {
        when(port.clear(1L, "白酒消费")).thenReturn(5);

        String result = tool.clearWatchlist(1L, "白酒消费");
        assertTrue(result.contains("SUCCESS"));
        assertTrue(result.contains("5"));
    }

    @Test
    @DisplayName("查询自选股列表")
    void testQueryWatchlist() {
        StockWatchlistItem item = StockWatchlistItem.builder()
                .userId(1L)
                .stockCode("600519")
                .stockName("贵州茅台")
                .groupName("白酒消费")
                .targetPrice(new BigDecimal("1800.00"))
                .build();
        when(port.listByUser(1L, "白酒消费")).thenReturn(List.of(item));

        String result = tool.queryWatchlist(1L, "白酒消费");
        assertTrue(result.contains("600519"));
        assertTrue(result.contains("贵州茅台"));
    }
}
