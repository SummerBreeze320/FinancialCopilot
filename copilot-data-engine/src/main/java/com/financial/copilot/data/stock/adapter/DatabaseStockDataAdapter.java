package com.financial.copilot.data.stock.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financial.copilot.common.stock.dto.StockMetricsDTO;
import com.financial.copilot.common.stock.dto.StockScreeningCriteria;
import com.financial.copilot.data.stock.mapper.StockInfoMapper;
import com.financial.copilot.data.stock.po.StockInfoPO;
import com.financial.copilot.domain.stock.entity.StockInfo;
import com.financial.copilot.domain.stock.port.StockDataPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * <h1>数据库股票数据访问适配器 (Database Stock Data Adapter)</h1>
 * <p>
 * 职责：实现领域层定义的数据端口 {@link StockDataPort}，基于 MyBatis-Plus 驱动对底层股票基础信息表进行查询。
 * 包含防断流默认样本，确保在无网络或空表状态下平台能顺畅演示。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Repository
public class DatabaseStockDataAdapter implements StockDataPort {

    private final StockInfoMapper stockInfoMapper;

    /**
     * 构造函数，注入 MyBatis-Plus Mapper
     *
     * @param stockInfoMapper 股票基础信息映射器
     */
    public DatabaseStockDataAdapter(StockInfoMapper stockInfoMapper) {
        this.stockInfoMapper = stockInfoMapper;
    }

    @Override
    public Optional<StockInfo> getStockByCode(String stockCode) {
        try {
            StockInfoPO po = stockInfoMapper.selectById(stockCode);
            if (po != null) {
                return Optional.of(mapToEntity(po));
            }
        } catch (Exception e) {
            log.warn("[STOCK-ADAPTER] 数据库查询个股失败，返回模拟样本: code={}, error={}", stockCode, e.getMessage());
        }

        // 稳健兜底
        return Optional.of(StockInfo.builder()
                .stockCode(stockCode)
                .stockName("股票标的-" + stockCode)
                .exchange("SSE")
                .primarySector("食品饮料")
                .secondarySector("白酒")
                .totalMarketCapBillion(new BigDecimal("150.00"))
                .floatMarketCapBillion(new BigDecimal("140.00"))
                .listingDate(LocalDate.of(2001, 8, 27))
                .build());
    }

    @Override
    public List<StockInfo> screenStocks(StockScreeningCriteria criteria) {
        try {
            LambdaQueryWrapper<StockInfoPO> query = new LambdaQueryWrapper<>();
            if (criteria.sector() != null && !criteria.sector().isBlank()) {
                query.like(StockInfoPO::getPrimarySector, criteria.sector());
            }
            if (criteria.maxPeTtm() != null) {
                query.le(StockInfoPO::getPeTtm, criteria.maxPeTtm());
            }
            if (criteria.minRoe() != null) {
                query.ge(StockInfoPO::getRoe, criteria.minRoe());
            }
            if (criteria.minMarketCapBillion() != null) {
                query.ge(StockInfoPO::getTotalMarketCapBillion, criteria.minMarketCapBillion());
            }
            int limit = criteria.limit() != null ? criteria.limit() : 10;
            query.last("LIMIT " + limit);

            List<StockInfoPO> list = stockInfoMapper.selectList(query);
            if (list != null && !list.isEmpty()) {
                return list.stream().map(this::mapToEntity).toList();
            }
        } catch (Exception e) {
            log.warn("[STOCK-ADAPTER] 筛选股票查询异常，使用演示样本: error={}", e.getMessage());
        }

        // 兜底标的列表
        return List.of(
                StockInfo.builder().stockCode("600519.SH").stockName("贵州茅台").exchange("SSE").primarySector("食品饮料").secondarySector("白酒").totalMarketCapBillion(new BigDecimal("2000.0")).floatMarketCapBillion(new BigDecimal("2000.0")).build(),
                StockInfo.builder().stockCode("000858.SZ").stockName("五粮液").exchange("SZSE").primarySector("食品饮料").secondarySector("白酒").totalMarketCapBillion(new BigDecimal("500.0")).floatMarketCapBillion(new BigDecimal("500.0")).build(),
                StockInfo.builder().stockCode("600276.SH").stockName("恒瑞医药").exchange("SSE").primarySector("医药生物").secondarySector("化学制药").totalMarketCapBillion(new BigDecimal("280.0")).floatMarketCapBillion(new BigDecimal("280.0")).build()
        );
    }

    @Override
    public StockMetricsDTO getStockMetrics(String stockCode) {
        StockInfo info = getStockByCode(stockCode).orElse(null);
        BigDecimal pe = new BigDecimal("20.0");
        BigDecimal pb = new BigDecimal("2.5");
        BigDecimal roe = new BigDecimal("16.0");
        BigDecimal div = new BigDecimal("2.0");

        try {
            StockInfoPO po = stockInfoMapper.selectById(stockCode);
            if (po != null) {
                if (po.getPeTtm() != null) pe = po.getPeTtm();
                if (po.getPb() != null) pb = po.getPb();
                if (po.getRoe() != null) roe = po.getRoe();
                if (po.getDividendYield() != null) div = po.getDividendYield();
            }
        } catch (Exception ignored) {
        }

        return StockMetricsDTO.builder()
                .stockCode(stockCode)
                .stockName(info != null ? info.getStockName() : "标的-" + stockCode)
                .sector(info != null ? info.getPrimarySector() : "消费")
                .latestPrice(new BigDecimal("150.00"))
                .peTtm(pe)
                .pb(pb)
                .roe(roe)
                .dividendYield(div)
                .totalMarketCapBillion(info != null ? info.getTotalMarketCapBillion() : new BigDecimal("100.0"))
                .beta(new BigDecimal("1.05"))
                .updateDate(LocalDate.now())
                .build();
    }

    private StockInfo mapToEntity(StockInfoPO po) {
        return StockInfo.builder()
                .stockCode(po.getStockCode())
                .stockName(po.getStockName())
                .exchange(po.getExchange())
                .primarySector(po.getPrimarySector())
                .secondarySector(po.getSecondarySector())
                .totalMarketCapBillion(po.getTotalMarketCapBillion())
                .floatMarketCapBillion(po.getFloatMarketCapBillion())
                .listingDate(po.getListingDate())
                .build();
    }
}
