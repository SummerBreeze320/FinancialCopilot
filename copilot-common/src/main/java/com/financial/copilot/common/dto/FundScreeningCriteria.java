package com.financial.copilot.common.dto;

import java.io.Serializable;

/**
 * 基金智能多维筛选强类型 DSL 条件对象
 * 由大模型将用户的自然语言解析填充至本结构体，杜绝裸拼 SQL。
 */
public record FundScreeningCriteria(
        // 资产门类: 股票型 / 偏股混合型 / 债券型 / 指数型 (null 表示不限)
        String fundType,

        // 主题或行业板块: 医药 / 半导体 / 消费 / 新能源 / 军工 / 红利
        String sectorTheme,

        // 最低资产规模 (亿元)
        Double minScaleInBillion,

        // 最大资产规模 (亿元)
        Double maxScaleInBillion,

        // 近三年最大回撤上限 (%, 例如 15.0 表示最大回撤不超过 15%)
        Double maxDrawdown3YLimit,

        // 近三年最低夏普比率 (例如 1.2)
        Double minSharpe3Y,

        // 近三年最低年化回报率 (%)
        Double minReturn3Y,

        // 基金经理最低任职年限 (年)
        Integer minManagerTenureYears,

        // 排序字段: RETURN_3Y, SHARPE_3Y, MAX_DRAWDOWN_3Y, SCALE
        String sortBy,

        // 排序方向: ASC, DESC (默认 DESC)
        String sortOrder,

        // 返回最大数量限制 (默认 10)
        Integer limit
) implements Serializable {

    public FundScreeningCriteria {
        if (limit == null || limit <= 0) {
            limit = 10;
        }
        if (sortOrder == null || sortOrder.isBlank()) {
            sortOrder = "DESC";
        }
    }
}
