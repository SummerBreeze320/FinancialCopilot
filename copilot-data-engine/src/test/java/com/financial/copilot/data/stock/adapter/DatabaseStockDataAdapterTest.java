package com.financial.copilot.data.stock.adapter;

import com.financial.copilot.common.stock.dto.StockMetricsDTO;
import com.financial.copilot.common.stock.dto.StockScreeningCriteria;
import com.financial.copilot.data.stock.mapper.StockDailyKlineMapper;
import com.financial.copilot.data.stock.mapper.StockDividendMapper;
import com.financial.copilot.data.stock.mapper.StockFinancialMapper;
import com.financial.copilot.data.stock.mapper.StockInfoMapper;
import com.financial.copilot.data.stock.mapper.StockShareholderMapper;
import com.financial.copilot.domain.business.stock.entity.StockInfo;
import com.financial.copilot.domain.business.stock.port.StockDataPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>股票数据适配器单元测试 (Database Stock Data Adapter Test)</h1>
 * <p>
 * 测试验证 {@link DatabaseStockDataAdapter} 对股票查询、多因子初筛与量化财务指标组装的鲁棒性。
 * </p>
 *
 * @author FinancialCopilot
 */
class DatabaseStockDataAdapterTest {

    private DatabaseStockDataAdapter adapter;
    private StockInfoMapper mockMapper;

    @BeforeEach
    void setUp() {
        mockMapper = Mockito.mock(StockInfoMapper.class);
        StockFinancialMapper mockFinancialMapper = Mockito.mock(StockFinancialMapper.class);
        StockDividendMapper mockDividendMapper = Mockito.mock(StockDividendMapper.class);
        StockShareholderMapper mockShareholderMapper = Mockito.mock(StockShareholderMapper.class);
        StockDailyKlineMapper mockKlineMapper = Mockito.mock(StockDailyKlineMapper.class);
        adapter = new DatabaseStockDataAdapter(mockMapper, mockFinancialMapper, mockDividendMapper, mockShareholderMapper, mockKlineMapper);
    }

    /**
     * 测试验证根据股票代码获取标的（包括兜底保护）
     */
    @Test
    @DisplayName("验证按代码查询股票信息")
    void testGetStockByCode() {
        Optional<StockInfo> stockOpt = adapter.getStockByCode("600519.SH");
        assertTrue(stockOpt.isPresent());
        StockInfo stock = stockOpt.get();
        assertEquals("600519.SH", stock.getStockCode());
        assertNotNull(stock.getStockName());
    }

    /**
     * 测试验证股票多因子初筛返回标的池
     */
    @Test
    @DisplayName("验证股票多因子初筛")
    void testScreenStocks() {
        StockScreeningCriteria criteria = new StockScreeningCriteria(
                "SSE", "白酒", 100.0, 30.0, null, 15.0, 2.0, "MARKET_CAP", "DESC", 5
        );
        List<StockInfo> results = adapter.screenStocks(criteria);
        assertNotNull(results);
        assertFalse(results.isEmpty());
    }

    /**
     * 测试验证股票量化与基本面指标组装
     */
    @Test
    @DisplayName("验证个股量化指标提取")
    void testGetStockMetrics() {
        StockMetricsDTO metrics = adapter.getStockMetrics("600519.SH");
        assertNotNull(metrics);
        assertEquals("600519.SH", metrics.getStockCode());
        assertNotNull(metrics.getPeTtm());
        assertNotNull(metrics.getRoe());
    }

    @Test
    @DisplayName("验证财务历史查询返回空列表而非异常")
    void testGetFinancialHistory_empty() {
        List<StockDataPort.StockFinancialSummary> result = adapter.getFinancialHistory("600519.SH", 4);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("验证分红历史查询返回空列表而非异常")
    void testGetDividendHistory_empty() {
        List<StockDataPort.StockDividendRecord> result = adapter.getDividendHistory("600519.SH");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("验证十大股东查询返回空列表而非异常")
    void testGetTopShareholders_empty() {
        List<StockDataPort.StockShareholderRecord> result = adapter.getTopShareholders("600519.SH");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("验证日K线收盘价查询返回空列表而非异常")
    void testGetDailyClosePrices_empty() {
        List<java.math.BigDecimal> result = adapter.getDailyClosePrices("600519.SH", 60);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("验证按代码或关键词模糊搜索股票")
    void testSearchStocksByCodeOrName() {
        List<StockInfo> result = adapter.searchStocks("茅台", 5);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals("贵州茅台", result.getFirst().getStockName());
    }
}
