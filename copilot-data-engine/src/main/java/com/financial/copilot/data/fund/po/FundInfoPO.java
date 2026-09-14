package com.financial.copilot.data.fund.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <h1>基金基础信息持久化实体 (Fund Info PO)</h1>
 * <p>
 * 对应数据库物理表: {@code fund_info}
 * 记录基金的基础静态属性、管理机构、规模及业绩比较基准。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("fund_info")
public class FundInfoPO {

    /**
     * 基金代码（主键，例如 "005827.OF", "110011.OF"）
     */
    @TableId(value = "fund_code", type = IdType.INPUT)
    private String fundCode;

    /**
     * 基金全称或标准简称（例如 "易方达蓝筹精选混合"）
     */
    private String fundName;

    /**
     * 基金类型分类（混合型、股票型、债券型、指数型/ETF、QDII等）
     */
    private String fundType;

    /**
     * 基金成立日期
     */
    private LocalDate establishmentDate;

    /**
     * 基金管理人/基金公司机构 ID（关联 {@code fund_company.company_id}）
     */
    private String managementCompanyId;

    /**
     * 当前基金净资产总规模（单位：亿元）
     */
    private BigDecimal currentScaleBillion;

    /**
     * 业绩比较基准描述
     */
    private String trackingBenchmark;

    /**
     * 托管银行名称
     */
    private String custodianBank;

    /**
     * 记录创建时间戳
     */
    private LocalDateTime createdAt;
}
