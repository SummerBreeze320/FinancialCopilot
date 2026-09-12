package com.financial.copilot.data.fund.adapter;

import com.financial.copilot.common.fund.dto.FundMetricsDTO;
import com.financial.copilot.common.fund.dto.FundScreeningCriteria;
import com.financial.copilot.data.fund.mapper.*;
import com.financial.copilot.data.fund.po.*;
import com.financial.copilot.domain.fund.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * <h1>基金数据适配器单元测试 (Database Fund Data Adapter Test)</h1>
 * <p>
 * 测试验证 {@link DatabaseFundDataAdapter} 针对基金档案、持仓穿透、历史净值时序与量化指标计算的正确性。
 * </p>
 */
class DatabaseFundDataAdapterTest {

    private DatabaseFundDataAdapter adapter;
    private FundInfoMapper mockFundInfoMapper;
    private FundNavHistoryMapper mockNavHistoryMapper;
    private FundQuarterlyHoldingMapper mockHoldingMapper;
    private FundManagerMapper mockManagerMapper;
    private FundCompanyMapper mockCompanyMapper;

    @BeforeEach
    void setUp() {
        mockFundInfoMapper = Mockito.mock(FundInfoMapper.class);
        mockNavHistoryMapper = Mockito.mock(FundNavHistoryMapper.class);
        mockHoldingMapper = Mockito.mock(FundQuarterlyHoldingMapper.class);
        mockManagerMapper = Mockito.mock(FundManagerMapper.class);
        mockCompanyMapper = Mockito.mock(FundCompanyMapper.class);

        adapter = new DatabaseFundDataAdapter(
                mockFundInfoMapper,
                mockNavHistoryMapper,
                mockHoldingMapper,
                mockManagerMapper,
                mockCompanyMapper
        );
    }

    @Test
    @DisplayName("验证按基金代码获取基金基础信息")
    void testGetFundByCode() {
        FundInfoPO po = FundInfoPO.builder()
                .fundCode("005827")
                .fundName("易方达蓝筹精选混合")
                .fundType("偏股混合型基金")
                .establishmentDate(LocalDate.of(2018, 9, 5))
                .managementCompanyId("COMP_EFUND")
                .currentScaleBillion(new BigDecimal("204.16"))
                .trackingBenchmark("沪深300指数收益率*45%+中证港股通综合指数收益率*35%+中债总指数收益率*20%")
                .custodianBank("中国银行股份有限公司")
                .build();

        when(mockFundInfoMapper.selectById("005827")).thenReturn(po);

        Optional<FundInfo> result = adapter.getFundByCode("005827");
        assertTrue(result.isPresent());
        assertEquals("005827", result.get().getFundCode());
        assertEquals("易方达蓝筹精选混合", result.get().getFundName());
        assertEquals(new BigDecimal("204.16"), result.get().getCurrentScaleBillion());
    }

    @Test
    @DisplayName("验证基金前十大重仓持仓穿透查询")
    void testGetHoldings() {
        List<FundQuarterlyHoldingPO> holdingPOs = List.of(
                FundQuarterlyHoldingPO.builder()
                        .fundCode("005827")
                        .reportQuarter("2024Q2")
                        .rankOrder(1)
                        .stockCode("0700")
                        .stockName("腾讯控股")
                        .holdingRatio(new BigDecimal("18.98"))
                        .holdingSharesTenThousand(new BigDecimal("1140.00"))
                        .holdingSector("传媒/互联网")
                        .build(),
                FundQuarterlyHoldingPO.builder()
                        .fundCode("005827")
                        .reportQuarter("2024Q2")
                        .rankOrder(2)
                        .stockCode("0883")
                        .stockName("中国海洋石油")
                        .holdingRatio(new BigDecimal("18.98"))
                        .holdingSharesTenThousand(new BigDecimal("18950.00"))
                        .holdingSector("先进制造/核心资产")
                        .build()
        );

        when(mockHoldingMapper.selectList(any())).thenReturn(holdingPOs);

        List<FundQuarterlyHolding> holdings = adapter.getHoldings("005827", "2024Q2");
        assertNotNull(holdings);
        assertEquals(2, holdings.size());
        assertEquals("腾讯控股", holdings.get(0).getStockName());
        assertEquals(1, holdings.get(0).getRankOrder());
    }

    @Test
    @DisplayName("验证基于日复权净值时序的量化收益风险指标计算 (夏普率、卡玛比率、最大回撤)")
    void testGetFundMetrics() {
        FundInfoPO fundPO = FundInfoPO.builder()
                .fundCode("005827")
                .fundName("易方达蓝筹精选混合")
                .fundType("偏股混合型基金")
                .build();
        when(mockFundInfoMapper.selectById("005827")).thenReturn(fundPO);

        List<FundNavHistoryPO> navList = List.of(
                FundNavHistoryPO.builder().fundCode("005827").navDate(LocalDate.of(2023, 9, 1)).adjustedNav(new BigDecimal("1.5000")).dailyGrowthRate(BigDecimal.ZERO).build(),
                FundNavHistoryPO.builder().fundCode("005827").navDate(LocalDate.of(2023, 12, 1)).adjustedNav(new BigDecimal("1.4000")).dailyGrowthRate(new BigDecimal("-6.67")).build(),
                FundNavHistoryPO.builder().fundCode("005827").navDate(LocalDate.of(2024, 3, 1)).adjustedNav(new BigDecimal("1.6500")).dailyGrowthRate(new BigDecimal("17.86")).build(),
                FundNavHistoryPO.builder().fundCode("005827").navDate(LocalDate.of(2024, 9, 10)).adjustedNav(new BigDecimal("1.8000")).dailyGrowthRate(new BigDecimal("9.09")).build()
        );
        when(mockNavHistoryMapper.selectList(any())).thenReturn(navList);

        FundMetricsDTO metrics = adapter.getFundMetrics("005827", LocalDate.of(2023, 9, 1), LocalDate.of(2024, 9, 10));

        assertNotNull(metrics);
        assertEquals("005827", metrics.getFundCode());
        assertEquals("易方达蓝筹精选混合", metrics.getFundName());
        assertTrue(metrics.getCumulativeReturn().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(metrics.getAnnualizedReturn().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(metrics.getMaxDrawdown().compareTo(BigDecimal.ZERO) >= 0);
    }
}
