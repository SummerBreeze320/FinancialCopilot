package com.financial.copilot.common.stock.dto;

import java.io.Serializable;

/**
 * <h1>股票标的多维量化与基本面筛选条件传输对象 (DSL)</h1>
 * <p>
 * 为投研平台中的股票领域预留的标准筛选契约，支持市场交易所、行业、估值（PE/PB）、盈利能力（ROE）与分红率等复合过滤。
 * </p>
 *
 * @param exchange             交易所代码：如 "SSE" (上交所)、"SZSE" (深交所)、"BSE" (北交所)、"HKEX" (港交所)
 * @param sector               所属行业板块 (如: "白酒"、"创新药"、"半导体制造")
 * @param minMarketCapBillion  总市值下限 (单位: 亿元)
 * @param maxPeTtm             滚动市盈率上限 (PE-TTM)
 * @param maxPb                市净率上限 (PB)
 * @param minRoe               加权净资产收益率下限 (ROE, 单位: %)
 * @param minDividendYield     股息率下限 (单位: %)
 * @param sortBy               排序字段 (如: MARKET_CAP, ROE, PE, DIVIDEND_YIELD)
 * @param sortOrder            排序方向 (ASC / DESC)
 * @param limit                返回最大数量限制
 * @author FinancialCopilot
 */
public record StockScreeningCriteria(
        String exchange,
        String sector,
        Double minMarketCapBillion,
        Double maxPeTtm,
        Double maxPb,
        Double minRoe,
        Double minDividendYield,
        String sortBy,
        String sortOrder,
        Integer limit
) implements Serializable {

    public StockScreeningCriteria {
        if (limit == null || limit <= 0) {
            limit = 10;
        }
        if (sortOrder == null || sortOrder.isBlank()) {
            sortOrder = "DESC";
        }
    }
}
