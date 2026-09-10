package com.financial.copilot.domain.billing.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <h1>不可篡改 Token 消费记账流水实体 (Token Usage Ledger Entity)</h1>
 * <p>
 * 职责：记录系统每一次 Agent 步骤调用大模型的 Token 明细与扣费对账流水。
 * 为客户账单查询、对账审计与财务报表提供底层数据支撑。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsageLedger implements Serializable {

    /**
     * 账单流水主键 ID
     */
    private Long id;

    /**
     * 扣费归属系统用户 ID
     */
    private Long userId;

    /**
     * 投研任务会话或上下文追踪唯一 ID
     */
    private String sessionId;

    /**
     * 任务步骤类型 (SCREENING, BATCH_ANALYSIS, COMPARISON, SYNTHESIS, GENERAL_QA)
     */
    private String taskType;

    /**
     * 实际调用的底层厂商标识 (DEEPSEEK, OPENAI, QWEN 等)
     */
    private String provider;

    /**
     * 实际调用的模型名称标识 (如 deepseek-reasoner, deepseek-chat)
     */
    private String model;

    /**
     * 输入提示词 Token 数量 (Prompt Tokens)
     */
    private Integer promptTokens;

    /**
     * 大模型补全生成 Token 数量 (Completion Tokens)
     */
    private Integer completionTokens;

    /**
     * 本次交互合计总 Token 数
     */
    private Integer totalTokens;

    /**
     * 本次扣除的智算点总额
     */
    private Long consumedPoints;

    /**
     * 大模型端到端响应耗时 (毫秒)
     */
    private Integer latencyMs;

    /**
     * 流水扣费生成与入账时间
     */
    private LocalDateTime createdAt;
}
