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
 * <h1>基金定期报告定性文本及高维向量持久化实体 (Fund Report Vector PO)</h1>
 * <p>
 * 对应数据库物理表: {@code fund_report_vector}
 * 存储基金经理在季报、年报中披露的定性市场展望与运作回顾切片及其对应的语义高维嵌入向量 (PGVector: vector(1536))。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("fund_report_vector")
public class FundReportVectorPO {

    /**
     * 自增主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 基金代码（如 "005827.OF"）
     */
    private String fundCode;

    /**
     * 撰写报告的在任基金经理姓名
     */
    private String managerName;

    /**
     * 定期报告所属季度（例如 "2024Q3"）
     */
    private String reportQuarter;

    /**
     * 报告章节标题（例如 "投资策略和运作分析", "对宏观经济与市场的展望"）
     */
    private String sectionTitle;

    /**
     * 报告原始定性段落正文文本
     */
    private String content;

    /**
     * 文本高维向量切片 (PGVector: vector(1536))
     * 存储格式形如: "[0.0123,-0.0456,...]"
     */
    private String embedding;

    /**
     * 向量切片入库时间戳
     */
    private LocalDateTime createdAt;
}
