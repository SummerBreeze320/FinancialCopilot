package com.financial.copilot.data.stock.adapter;

import com.financial.copilot.common.stock.dto.StockMetricsDTO;
import com.financial.copilot.common.stock.dto.StockScreeningCriteria;
import com.financial.copilot.data.stock.mapper.StockInfoMapper;
import com.financial.copilot.domain.stock.entity.StockInfo;
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
        adapter = new DatabaseStockDataAdapter(mockMapper);
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
}
