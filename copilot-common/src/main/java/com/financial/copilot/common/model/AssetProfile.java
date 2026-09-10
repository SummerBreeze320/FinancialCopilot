package com.financial.copilot.common.model;

import com.financial.copilot.common.enums.AssetCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 统一资产简档模型
 * 无论公募基金、股票、期货还是银行理财，对外提供标准的基础标的档案契约
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetProfile {

    /**
     * 标的代码 (如基金代码 005827、股票代码 600519)
     */
    private String assetCode;

    /**
     * 标的名称 (如 易方达蓝筹精选混合、贵州茅台)
     */
    private String assetName;

    /**
     * 资产大类
     */
    private AssetCategory category;

    /**
     * 发行管理机构或交易所
     */
    private String issuerOrExchange;

    /**
     * 最新单位净值或最新市价
     */
    private BigDecimal latestPriceOrNav;

    /**
     * 最新数据更新日期
     */
    private LocalDate updateDate;

    /**
     * 资产特定描述或业绩基准
     */
    private String benchmarkOrDescription;
}
