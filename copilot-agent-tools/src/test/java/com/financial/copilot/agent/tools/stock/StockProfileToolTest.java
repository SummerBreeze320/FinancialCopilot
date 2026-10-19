package com.financial.copilot.agent.tools.stock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.common.stock.dto.StockMetricsDTO;
import com.financial.copilot.domain.business.stock.entity.StockInfo;
import com.financial.copilot.domain.business.stock.port.StockDataPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("StockProfileTool 股票全维属性档案查询测试")
class StockProfileToolTest {

    private StockDataPort stockDataPort;
    private ObjectMapper objectMapper;
    private StockProfileTool tool;

    @BeforeEach
    void setUp() {
        stockDataPort = mock(StockDataPort.class);
        objectMapper = new ObjectMapper();
        tool = new StockProfileTool(stockDataPort, objectMapper);
    }

    @Test
    @DisplayName("按6位股票代码精确查询股票档案与估值指标")
    void testGetStockProfileByCode() {
        StockInfo info = StockInfo.builder()
                .stockCode("600519")
                .stockName("贵州茅台")
                .exchange("SH")
                .primarySector("食品饮料")
                .totalMarketCapBillion(new BigDecimal("2100.0"))
                .build();
        when(stockDataPort.getStockByCode("600519")).thenReturn(Optional.of(info));
        when(stockDataPort.getStockMetrics("600519")).thenReturn(StockMetricsDTO.builder()
                .stockCode("600519")
                .stockName("贵州茅台")
                .sector("食品饮料")
                .peTtm(new BigDecimal("28.5"))
                .pb(new BigDecimal("8.2"))
                .roe(new BigDecimal("31.5"))
                .dividendYield(new BigDecimal("2.8"))
                .build());

        String json = tool.getStockProfile("600519");
        assertThat(json).contains("SUCCESS")
                .contains("600519")
                .contains("贵州茅台")
                .contains("食品饮料")
                .contains("28.5");
    }

    @Test
    @DisplayName("按中文名称关键词模糊搜索股票档案")
    void testGetStockProfileByKeyword() {
        StockInfo info = StockInfo.builder()
                .stockCode("600519")
                .stockName("贵州茅台")
                .exchange("SH")
                .primarySector("食品饮料")
                .build();
        when(stockDataPort.searchStocks(eq("茅台"), anyInt())).thenReturn(List.of(info));
        when(stockDataPort.getStockMetrics("600519")).thenReturn(StockMetricsDTO.builder()
                .stockCode("600519")
                .stockName("贵州茅台")
                .sector("食品饮料")
                .peTtm(new BigDecimal("28.5"))
                .build());

        String json = tool.getStockProfile("茅台");
        assertThat(json).contains("SUCCESS")
                .contains("600519")
                .contains("贵州茅台");
    }

    @Test
    @DisplayName("未查找到标的时返回友好 NOT_FOUND 结果")
    void testGetStockProfileNotFound() {
        when(stockDataPort.searchStocks(eq("未知股票"), anyInt())).thenReturn(List.of());

        String json = tool.getStockProfile("未知股票");
        assertThat(json).contains("NOT_FOUND");
    }
}
