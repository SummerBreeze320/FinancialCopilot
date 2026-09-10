package com.financial.copilot.data.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financial.copilot.common.dto.FundMetricsDTO;
import com.financial.copilot.common.dto.FundScreeningCriteria;
import com.financial.copilot.data.fund.mapper.*;
import com.financial.copilot.data.fund.po.*;
import com.financial.copilot.domain.entity.*;
import com.financial.copilot.domain.port.FundDataPort;
import com.financial.copilot.math.FinancialMathUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 基于 PostgreSQL 关系表与 MyBatis-Plus 的 FundDataPort SPI 实现
 */
@Component
public class DatabaseFundDataAdapter implements FundDataPort {

    private final FundInfoMapper fundInfoMapper;
    private final FundNavHistoryMapper navHistoryMapper;
    private final FundQuarterlyHoldingMapper holdingMapper;
    private final FundManagerMapper managerMapper;
    private final FundCompanyMapper companyMapper;

    public DatabaseFundDataAdapter(FundInfoMapper fundInfoMapper,
                                   FundNavHistoryMapper navHistoryMapper,
                                   FundQuarterlyHoldingMapper holdingMapper,
                                   FundManagerMapper managerMapper,
                                   FundCompanyMapper companyMapper) {
        this.fundInfoMapper = fundInfoMapper;
        this.navHistoryMapper = navHistoryMapper;
        this.holdingMapper = holdingMapper;
        this.managerMapper = managerMapper;
        this.companyMapper = companyMapper;
    }

    @Override
    public Optional<FundInfo> getFundByCode(String fundCode) {
        FundInfoPO po = fundInfoMapper.selectById(fundCode);
        return Optional.ofNullable(po).map(this::toFundInfo);
    }

    @Override
    public List<FundInfo> screenFunds(FundScreeningCriteria criteria) {
        LambdaQueryWrapper<FundInfoPO> wrapper = new LambdaQueryWrapper<>();

        if (criteria.fundType() != null && !criteria.fundType().isBlank()) {
            wrapper.eq(FundInfoPO::getFundType, criteria.fundType());
        }
        if (criteria.minScaleInBillion() != null) {
            wrapper.ge(FundInfoPO::getCurrentScaleBillion, BigDecimal.valueOf(criteria.minScaleInBillion()));
        }
        if (criteria.maxScaleInBillion() != null) {
            wrapper.le(FundInfoPO::getCurrentScaleBillion, BigDecimal.valueOf(criteria.maxScaleInBillion()));
        }
        if (criteria.sectorTheme() != null && !criteria.sectorTheme().isBlank()) {
            wrapper.like(FundInfoPO::getFundName, criteria.sectorTheme());
        }

        if ("ASC".equalsIgnoreCase(criteria.sortOrder())) {
            wrapper.orderByAsc(FundInfoPO::getCurrentScaleBillion);
        } else {
            wrapper.orderByDesc(FundInfoPO::getCurrentScaleBillion);
        }

        int limit = (criteria.limit() != null && criteria.limit() > 0) ? criteria.limit() : 10;
        wrapper.last("LIMIT " + limit);

        List<FundInfoPO> list = fundInfoMapper.selectList(wrapper);
        return list.stream().map(this::toFundInfo).toList();
    }

    @Override
    public List<FundNavHistory> getNavHistory(String fundCode, LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<FundNavHistoryPO> wrapper = new LambdaQueryWrapper<FundNavHistoryPO>()
                .eq(FundNavHistoryPO::getFundCode, fundCode);

        if (startDate != null) {
            wrapper.ge(FundNavHistoryPO::getNavDate, startDate);
        }
        if (endDate != null) {
            wrapper.le(FundNavHistoryPO::getNavDate, endDate);
        }
        wrapper.orderByAsc(FundNavHistoryPO::getNavDate);

        List<FundNavHistoryPO> list = navHistoryMapper.selectList(wrapper);
        return list.stream().map(this::toNavHistory).toList();
    }

    @Override
    public List<FundQuarterlyHolding> getHoldings(String fundCode, String reportQuarter) {
        LambdaQueryWrapper<FundQuarterlyHoldingPO> wrapper = new LambdaQueryWrapper<FundQuarterlyHoldingPO>()
                .eq(FundQuarterlyHoldingPO::getFundCode, fundCode);

        if (reportQuarter != null && !reportQuarter.isBlank()) {
            wrapper.eq(FundQuarterlyHoldingPO::getReportQuarter, reportQuarter);
        }
        wrapper.orderByAsc(FundQuarterlyHoldingPO::getRankOrder);

        List<FundQuarterlyHoldingPO> list = holdingMapper.selectList(wrapper);
        return list.stream().map(this::toHolding).toList();
    }

    @Override
    public FundMetricsDTO getFundMetrics(String fundCode, LocalDate startDate, LocalDate endDate) {
        FundInfoPO fundPO = fundInfoMapper.selectById(fundCode);
        String fundName = fundPO != null ? fundPO.getFundName() : fundCode;
        String fundType = fundPO != null ? fundPO.getFundType() : "混合型";

        List<FundNavHistoryPO> navList = getNavHistoryPOList(fundCode, startDate, endDate);

        if (navList.isEmpty()) {
            return FundMetricsDTO.builder()
                    .fundCode(fundCode)
                    .fundName(fundName)
                    .fundType(fundType)
                    .startDate(startDate)
                    .endDate(endDate)
                    .cumulativeReturn(BigDecimal.ZERO)
                    .annualizedReturn(BigDecimal.ZERO)
                    .maxDrawdown(BigDecimal.ZERO)
                    .annualizedVolatility(BigDecimal.ZERO)
                    .sharpeRatio(BigDecimal.ZERO)
                    .calmarRatio(BigDecimal.ZERO)
                    .top10Concentration(BigDecimal.ZERO)
                    .build();
        }

        List<BigDecimal> navSeries = navList.stream().map(FundNavHistoryPO::getAdjustedNav).toList();
        BigDecimal firstNav = navSeries.get(0);
        BigDecimal lastNav = navSeries.get(navSeries.size() - 1);

        LocalDate actualStart = navList.get(0).getNavDate();
        LocalDate actualEnd = navList.get(navList.size() - 1).getNavDate();
        long days = Math.max(1, ChronoUnit.DAYS.between(actualStart, actualEnd));

        // 严格调用高精度纯 Java 纯数学计算引擎
        BigDecimal cumulativeReturn = FinancialMathUtils.calculateCumulativeReturn(firstNav, lastNav);
        BigDecimal annualizedReturn = FinancialMathUtils.calculateAnnualizedReturn(firstNav, lastNav, days);
        BigDecimal maxDrawdown = FinancialMathUtils.calculateMaxDrawdown(navSeries);

        // 年化波动率估算 (基于日收益率标准差 * sqrt(250))
        BigDecimal annualizedVolatility = calculateAnnualizedVolatility(navList);
        BigDecimal riskFreeRate = new BigDecimal("2.50"); // 默认中国国债无风险基准利率 2.5%
        BigDecimal sharpeRatio = FinancialMathUtils.calculateSharpeRatio(annualizedReturn, riskFreeRate, annualizedVolatility);
        BigDecimal calmarRatio = FinancialMathUtils.calculateCalmarRatio(annualizedReturn, maxDrawdown);

        // 持仓穿透特征
        List<FundQuarterlyHoldingPO> holdings = holdingMapper.selectList(
                new LambdaQueryWrapper<FundQuarterlyHoldingPO>()
                        .eq(FundQuarterlyHoldingPO::getFundCode, fundCode)
                        .orderByAsc(FundQuarterlyHoldingPO::getRankOrder)
        );

        BigDecimal top10Concentration = BigDecimal.ZERO;
        String primarySector = "未知";
        BigDecimal primarySectorRatio = BigDecimal.ZERO;

        if (!holdings.isEmpty()) {
            top10Concentration = holdings.stream()
                    .map(FundQuarterlyHoldingPO::getHoldingRatio)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            primarySector = holdings.get(0).getHoldingSector();
            primarySectorRatio = holdings.get(0).getHoldingRatio();
        }

        return FundMetricsDTO.builder()
                .fundCode(fundCode)
                .fundName(fundName)
                .fundType(fundType)
                .startDate(actualStart)
                .endDate(actualEnd)
                .cumulativeReturn(cumulativeReturn)
                .annualizedReturn(annualizedReturn)
                .maxDrawdown(maxDrawdown)
                .annualizedVolatility(annualizedVolatility)
                .sharpeRatio(sharpeRatio)
                .calmarRatio(calmarRatio)
                .top10Concentration(top10Concentration)
                .primarySector(primarySector)
                .primarySectorRatio(primarySectorRatio)
                .build();
    }

    @Override
    public Optional<FundManager> getManagerById(String managerId) {
        FundManagerPO po = managerMapper.selectById(managerId);
        return Optional.ofNullable(po).map(this::toManager);
    }

    @Override
    public List<FundManager> searchManagersByName(String managerName) {
        LambdaQueryWrapper<FundManagerPO> wrapper = new LambdaQueryWrapper<FundManagerPO>()
                .like(FundManagerPO::getManagerName, managerName);
        return managerMapper.selectList(wrapper).stream()
                .map(this::toManager)
                .toList();
    }

    @Override
    public List<FundInfo> getFundsByManagerId(String managerId) {
        return Collections.emptyList();
    }

    @Override
    public Optional<FundCompany> getCompanyById(String companyId) {
        FundCompanyPO po = companyMapper.selectById(companyId);
        return Optional.ofNullable(po).map(this::toCompany);
    }

    private List<FundNavHistoryPO> getNavHistoryPOList(String fundCode, LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<FundNavHistoryPO> wrapper = new LambdaQueryWrapper<FundNavHistoryPO>()
                .eq(FundNavHistoryPO::getFundCode, fundCode);
        if (startDate != null) {
            wrapper.ge(FundNavHistoryPO::getNavDate, startDate);
        }
        if (endDate != null) {
            wrapper.le(FundNavHistoryPO::getNavDate, endDate);
        }
        wrapper.orderByAsc(FundNavHistoryPO::getNavDate);
        return navHistoryMapper.selectList(wrapper);
    }

    private BigDecimal calculateAnnualizedVolatility(List<FundNavHistoryPO> navList) {
        if (navList.size() < 2) {
            return new BigDecimal("15.00");
        }
        double sum = 0.0;
        List<Double> returns = new ArrayList<>();
        for (FundNavHistoryPO po : navList) {
            if (po.getDailyGrowthRate() != null) {
                double r = po.getDailyGrowthRate().doubleValue();
                returns.add(r);
                sum += r;
            }
        }
        if (returns.size() < 2) {
            return new BigDecimal("15.00");
        }
        double mean = sum / returns.size();
        double variance = 0.0;
        for (double r : returns) {
            variance += Math.pow(r - mean, 2);
        }
        variance = variance / (returns.size() - 1);
        double dailyStd = Math.sqrt(variance);
        double annualizedVol = dailyStd * Math.sqrt(250);
        return BigDecimal.valueOf(annualizedVol).setScale(2, RoundingMode.HALF_UP);
    }

    private FundInfo toFundInfo(FundInfoPO po) {
        return FundInfo.builder()
                .fundCode(po.getFundCode())
                .fundName(po.getFundName())
                .fundType(po.getFundType())
                .establishmentDate(po.getEstablishmentDate())
                .managementCompanyId(po.getManagementCompanyId())
                .currentScaleBillion(po.getCurrentScaleBillion())
                .trackingBenchmark(po.getTrackingBenchmark())
                .custodianBank(po.getCustodianBank())
                .build();
    }

    private FundNavHistory toNavHistory(FundNavHistoryPO po) {
        return FundNavHistory.builder()
                .id(po.getId())
                .fundCode(po.getFundCode())
                .navDate(po.getNavDate())
                .unitNav(po.getUnitNav())
                .accumulatedNav(po.getAccumulatedNav())
                .adjustedNav(po.getAdjustedNav())
                .dailyGrowthRate(po.getDailyGrowthRate())
                .build();
    }

    private FundQuarterlyHolding toHolding(FundQuarterlyHoldingPO po) {
        return FundQuarterlyHolding.builder()
                .id(po.getId())
                .fundCode(po.getFundCode())
                .reportQuarter(po.getReportQuarter())
                .rankOrder(po.getRankOrder())
                .stockCode(po.getStockCode())
                .stockName(po.getStockName())
                .holdingRatio(po.getHoldingRatio())
                .holdingSharesTenThousand(po.getHoldingSharesTenThousand())
                .holdingSector(po.getHoldingSector())
                .build();
    }

    private FundManager toManager(FundManagerPO po) {
        return FundManager.builder()
                .managerId(po.getManagerId())
                .managerName(po.getManagerName())
                .companyId(po.getCompanyId())
                .gender(po.getGender())
                .education(po.getEducation())
                .workingDays(po.getWorkingDays())
                .currentTotalScaleBillion(po.getCurrentTotalScaleBillion())
                .bestFundCode(po.getBestFundCode())
                .bestFundReturn(po.getBestFundReturn())
                .build();
    }

    private FundCompany toCompany(FundCompanyPO po) {
        return FundCompany.builder()
                .companyId(po.getCompanyId())
                .companyName(po.getCompanyName())
                .shortName(po.getShortName())
                .establishmentDate(po.getEstablishmentDate())
                .totalScaleBillion(po.getTotalScaleBillion())
                .equityScaleBillion(po.getEquityScaleBillion())
                .managerCount(po.getManagerCount())
                .fundCount(po.getFundCount())
                .build();
    }
}
