package com.financial.copilot.data.fund.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 基金定期报告定性文本及向量切片 (MyBatis-Plus + PGVector)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("fund_report_vector")
public class FundReportVectorPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String fundCode;

    private String managerName;

    private String reportQuarter;

    private String sectionTitle;

    private String content;

    /**
     * 文本高维向量切片 (PGVector: vector(1536))
     * 存储格式形如: "[0.0123,-0.0456,...]"
     */
    private String embedding;

    private LocalDateTime createdAt;
}
