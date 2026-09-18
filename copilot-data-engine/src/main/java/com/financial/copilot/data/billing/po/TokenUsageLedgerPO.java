package com.financial.copilot.data.billing.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financial.copilot.domain.platform.billing.entity.TokenUsageLedger;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * <h1>Token 消费对账流水持久化对象 (MyBatis-Plus PO)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("llm_token_usage_ledger")
public class TokenUsageLedgerPO {

    /**
     * 自增主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 系统用户 ID
     */
    private Long userId;

    /**
     * 投研会话或分析 Run 批次 SessionId
     */
    private String sessionId;

    /**
     * 业务任务类型（如 RESEARCH, COMPARISON, SUMMARY, SCREENING）
     */
    private String taskType;

    /**
     * 模型供应商代码（如 DEEPSEEK, OPENAI, QWEN）
     */
    private String provider;

    /**
     * 调用的具体大模型名称（如 "deepseek-reasoner", "gpt-4o"）
     */
    private String model;

    /**
     * 输入提示词 Token 消耗数
     */
    private Integer promptTokens;

    /**
     * 补全回答 Token 消耗数
     */
    private Integer completionTokens;

    /**
     * 本次交互累计 Token 总数
     */
    private Integer totalTokens;

    /**
     * 依据阶梯定价折算扣减的算力积分数额
     */
    private Long consumedPoints;

    /**
     * 大模型网络往返与生成耗时（毫秒）
     */
    private Integer latencyMs;

    /**
     * 账单流水落库记录时间戳
     */
    private LocalDateTime createdAt;

    public TokenUsageLedger toDomain() {
        return TokenUsageLedger.builder()
                .id(id)
                .userId(userId)
                .sessionId(sessionId)
                .taskType(taskType)
                .provider(provider)
                .model(model)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .consumedPoints(consumedPoints)
                .latencyMs(latencyMs)
                .createdAt(createdAt)
                .build();
    }

    public static TokenUsageLedgerPO fromDomain(TokenUsageLedger domain) {
        if (domain == null) return null;
        return TokenUsageLedgerPO.builder()
                .id(domain.getId())
                .userId(domain.getUserId())
                .sessionId(domain.getSessionId())
                .taskType(domain.getTaskType())
                .provider(domain.getProvider())
                .model(domain.getModel())
                .promptTokens(domain.getPromptTokens())
                .completionTokens(domain.getCompletionTokens())
                .totalTokens(domain.getTotalTokens())
                .consumedPoints(domain.getConsumedPoints())
                .latencyMs(domain.getLatencyMs())
                .createdAt(domain.getCreatedAt())
                .build();
    }
}
