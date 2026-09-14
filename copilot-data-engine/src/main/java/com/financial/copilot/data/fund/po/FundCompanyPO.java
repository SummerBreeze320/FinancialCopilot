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
 * <h1>基金管理公司持久化实体 (Fund Company PO)</h1>
 * <p>
 * 对应数据库物理表: {@code fund_company}
 * 记录公募基金管理公司机构基本信息、管理总规模、权益规模及投研团队体量。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("fund_company")
public class FundCompanyPO {

    /**
     * 机构唯一标识 ID（主键，例如 "COMP000001"）
     */
    @TableId(value = "company_id", type = IdType.INPUT)
    private String companyId;

    /**
     * 基金公司全称（例如 "易方达基金管理有限公司"）
     */
    private String companyName;

    /**
     * 基金公司简称（例如 "易方达"）
     */
    private String shortName;

    /**
     * 公司成立日期
     */
    private LocalDate establishmentDate;

    /**
     * 公募资产管理总规模（单位：亿元）
     */
    private BigDecimal totalScaleBillion;

    /**
     * 权益类（股票型 + 偏股混合型）管理规模（单位：亿元）
     */
    private BigDecimal equityScaleBillion;

    /**
     * 旗下在任基金经理总人数
     */
    private Integer managerCount;

    /**
     * 旗下存续公募基金产品总只数
     */
    private Integer fundCount;

    /**
     * 记录创建时间戳
     */
    private LocalDateTime createdAt;
}
