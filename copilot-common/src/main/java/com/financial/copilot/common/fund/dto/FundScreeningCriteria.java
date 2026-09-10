package com.financial.copilot.common.fund.dto;

import java.io.Serializable;

/**
 * <h1>公募基金多维量化筛选条件传输对象 (DSL)</h1>
 * <p>
 * 该对象由大语言模型（LLM）或规则引擎解析用户的自然语言指令后生成，
 * 封装了针对基金标的的全部初筛过滤维度与排序规则，杜绝前端裸拼 SQL，保障查询安全。
 * </p>
 *
 * @param fundType              资产分类：如 "股票型"、"偏股混合型"、"债券型"、"指数型"，为空表示不限类型
 * @param sectorTheme           投资主题或行业板块：如 "医药"、"半导体"、"消费"、"新能源" 等
 * @param minScaleInBillion     基金资产规模下限（单位：亿元）
 * @param maxScaleInBillion     基金资产规模上限（单位：亿元）
 * @param maxDrawdown3YLimit    近三年最大回撤上限（百分比，如 20.0 表示回撤不能超过 20%）
 * @param minSharpe3Y           近三年最低夏普比率阈值（如 1.2）
 * @param minReturn3Y           近三年最低年化回报率阈值（百分比，如 15.0）
 * @param minManagerTenureYears 基金经理最低从业/在任年限（单位：年）
 * @param sortBy                排序字段枚举：RETURN_3Y（年化回报）、SHARPE_3Y（夏普比率）、MAX_DRAWDOWN_3Y（最大回撤）、SCALE（管理规模）
 * @param sortOrder             排序方向：ASC（升序）或 DESC（降序，默认降序）
 * @param limit                 返回的最大标的数量限制（默认 10）
 * @author FinancialCopilot
 */
public record FundScreeningCriteria(
        String fundType,
        String sectorTheme,
        Double minScaleInBillion,
        Double maxScaleInBillion,
        Double maxDrawdown3YLimit,
        Double minSharpe3Y,
        Double minReturn3Y,
        Integer minManagerTenureYears,
        String sortBy,
        String sortOrder,
        Integer limit
) implements Serializable {

    /**
     * 规范构造器：对参数进行默认值安全兜底处理
     */
    public FundScreeningCriteria {
        if (limit == null || limit <= 0) {
            limit = 10;
        }
        if (sortOrder == null || sortOrder.isBlank()) {
            sortOrder = "DESC";
        }
    }
}
