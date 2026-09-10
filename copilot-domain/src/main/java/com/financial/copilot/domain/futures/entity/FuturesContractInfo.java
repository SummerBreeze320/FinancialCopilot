package com.financial.copilot.domain.futures.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <h1>期货合约领域实体 (Futures Contract Entity)</h1>
 * <p>
 * 职责：代表期货金融衍生品合约的业务领域对象，涵盖大宗商品期货、股指期货、国债期货等标的。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FuturesContractInfo {

    /**
     * 期货合约代码（例如 "IF2412"、"RB2410"、"AU2412"）
     */
    private String contractCode;

    /**
     * 期货合约名称（例如 "沪深300股指期货2412"、"螺纹钢2410"）
     */
    private String contractName;

    /**
     * 所属交易所代码（CFFEX 中国金融期货交易所 / SHFE 上海期货交易所 / DCE 大连商品交易所 / CZCE 郑州商品交易所）
     */
    private String exchange;

    /**
     * 基础标的物（如 "沪深300指数"、"HRB400螺纹钢"）
     */
    private String underlyingAsset;

    /**
     * 合约乘数（如 300元/点 或 10吨/手）
     */
    private BigDecimal contractMultiplier;

    /**
     * 最低交易保证金比例 (%)
     */
    private BigDecimal marginRate;

    /**
     * 最后交易日 / 交割日
     */
    private LocalDate deliveryDate;
}
