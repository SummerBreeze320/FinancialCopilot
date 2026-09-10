package com.financial.copilot.data.billing.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financial.copilot.domain.billing.entity.TokenUsageLedger;
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

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String sessionId;

    private String taskType;

    private String provider;

    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Long consumedPoints;

    private Integer latencyMs;

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
