package com.financial.copilot.agent.core.agents;

import com.financial.copilot.agent.core.agents.fund.FundScreenerAgent;
import com.financial.copilot.agent.core.agents.stock.StockScreenerAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * <h1>金融筛选统一门面单元测试 (Multi-Asset Screener Agent Test)</h1>
 * <p>
 * 测试验证 {@link ScreenerAgent} 针对基金与股票自然语言诉求的智能路由分发。
 * </p>
 *
 * @author FinancialCopilot
 */
class MultiAssetScreenerAgentTest {

    /**
     * 测试验证基金类诉求自动路由至 FundScreenerAgent
     */
    @Test
    @DisplayName("验证基金筛选诉求正确路由给公募基金专员")
    void testRouteToFundScreener() {
        FundScreenerAgent mockFundScreener = Mockito.mock(FundScreenerAgent.class);
        StockScreenerAgent mockStockScreener = Mockito.mock(StockScreenerAgent.class);

        when(mockFundScreener.executeScreening(anyString())).thenReturn("[{\"fundCode\":\"005827\"}]");

        ScreenerAgent facade = new ScreenerAgent(mockFundScreener, mockStockScreener);
        String result = facade.executeScreening("帮我找近三年回撤小于15%的偏股混合型基金");

        assertEquals("[{\"fundCode\":\"005827\"}]", result);
        verify(mockFundScreener, times(1)).executeScreening(anyString());
        verify(mockStockScreener, never()).executeScreening(anyString());
    }

    /**
     * 测试验证股票类诉求自动路由至 StockScreenerAgent
     */
    @Test
    @DisplayName("验证股票筛选诉求正确路由给股票专员")
    void testRouteToStockScreener() {
        FundScreenerAgent mockFundScreener = Mockito.mock(FundScreenerAgent.class);
        StockScreenerAgent mockStockScreener = Mockito.mock(StockScreenerAgent.class);

        when(mockStockScreener.executeScreening(anyString())).thenReturn("[{\"stockCode\":\"600519.SH\"}]");

        ScreenerAgent facade = new ScreenerAgent(mockFundScreener, mockStockScreener);
        String result = facade.executeScreening("帮我找市盈率低于25倍、ROE大于15%的A股白酒股票龙头");

        assertEquals("[{\"stockCode\":\"600519.SH\"}]", result);
        verify(mockStockScreener, times(1)).executeScreening(anyString());
        verify(mockFundScreener, never()).executeScreening(anyString());
    }
}
