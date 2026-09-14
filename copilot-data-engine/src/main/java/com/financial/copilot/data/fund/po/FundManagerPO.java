package com.financial.copilot.data.fund.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <h1>基金经理信息持久化实体 (Fund Manager PO)</h1>
 * <p>
 * 对应数据库物理表: {@code fund_manager}
 * 记录基金经理画像、从业年限、当前管辖总规模及代表作回报率。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("fund_manager")
public class FundManagerPO {

    /**
     * 基金经理唯一标识 ID（主键，例如 "MGR000101"）
     */
    @TableId(value = "manager_id", type = IdType.INPUT)
    private String managerId;

    /**
     * 基金经理姓名（例如 "张坤"）
     */
    private String managerName;

    /**
     * 所属基金公司 ID（关联 {@code fund_company.company_id}）
     */
    private String companyId;

    /**
     * 性别（MALE / FEMALE）
     */
    private String gender;

    /**
     * 最高学历（硕士、博士等）
     */
    private String education;

    /**
     * 累计基金经理任职天数
     */
    private Integer workingDays;

    /**
     * 当前名下在管公募基金总规模（单位：亿元）
     */
    private BigDecimal currentTotalScaleBillion;

    /**
     * 个人历史任职代表作/最佳基金代码
     */
    private String bestFundCode;

    /**
     * 最佳基金任职期间累计年化或总回报率 (%)
     */
    private BigDecimal bestFundReturn;

    /**
     * 记录创建时间戳
     */
    private LocalDateTime createdAt;
}
