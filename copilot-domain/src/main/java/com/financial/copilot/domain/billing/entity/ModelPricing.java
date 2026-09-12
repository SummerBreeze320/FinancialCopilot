package com.financial.copilot.domain.billing.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * <h1>大模型动态阶梯定价规格领域实体 (Model Pricing Entity)</h1>
 * <p>
 * 职责：定义各厂商具体模型在输入 Token、输出 Token 及 Prompt Cache 命中时的点数折算单价。
 * 平台运营可后台动态调价与灰度。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelPricing implements Serializable {

    /**
     * 主键 ID
     */
    private Long id;

    /**
     * 厂商标识 (DEEPSEEK, OPENAI, QWEN, ZHIPU, OLLAMA, CUSTOM)
     */
    private String providerType;

    /**
     * 模型全局唯一标识 (如 deepseek-chat, deepseek-reasoner, gpt-4o)
     */
    private String modelName;

    /**
     * 每千输入 Token 扣减算力点数 (Points per 1k input tokens)
     */
    private BigDecimal inputPricePerK;

    /**
     * 每千输出 Token 扣减算力点数 (Points per 1k output tokens)
     */
    private BigDecimal outputPricePerK;

    /**
     * 每千上下文缓存命中 Token 优惠点数
     */
    private BigDecimal cacheHitPricePerK;

    /**
     * 定价策略是否上架生效
     */
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 根据输入与输出 Token 规模计算应扣除的整数算力点数
     *
     * @param promptTokens     输入 Token 数
     * @param completionTokens 输出 Token 数
     * @return 最终扣减点数（向上取整，最低扣 1 点）
     */
    public long calculatePoints(int promptTokens, int completionTokens) {
        if (promptTokens < 0 || completionTokens < 0) throw new IllegalArgumentException("Negative token usage");
        if (promptTokens == 0 && completionTokens == 0) return 0;
        BigDecimal promptCost = (inputPricePerK != null ? inputPricePerK : BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(promptTokens))
                .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);

        BigDecimal completionCost = (outputPricePerK != null ? outputPricePerK : BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(completionTokens))
                .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);

        BigDecimal total = promptCost.add(completionCost);
        long points = total.setScale(0, RoundingMode.CEILING).longValue();
        return Math.max(1L, points);
    }
}
