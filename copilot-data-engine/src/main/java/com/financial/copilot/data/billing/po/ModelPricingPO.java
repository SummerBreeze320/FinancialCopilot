package com.financial.copilot.data.billing.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financial.copilot.domain.platform.billing.entity.ModelPricing;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * <h1>大模型定价规格持久化对象 (MyBatis-Plus PO)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("llm_model_pricing")
public class ModelPricingPO {

    /**
     * 自增主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 模型供应商类型（如 DEEPSEEK, OPENAI, ANTHROPIC, QWEN, OLLAMA）
     */
    private String providerType;

    /**
     * 模型具体标识名称（如 "deepseek-reasoner", "gpt-4o", "qwen-max"）
     */
    private String modelName;

    /**
     * 每 1,000 输入 Token 消耗的算力积分
     */
    private BigDecimal inputPricePerK;

    /**
     * 每 1,000 输出 Token 消耗的算力积分
     */
    private BigDecimal outputPricePerK;

    /**
     * 每 1,000 命中文中缓存 (Context Cache Hit) 输入 Token 优惠计费积分
     */
    private BigDecimal cacheHitPricePerK;

    /**
     * 定价规则是否生效激活
     */
    private Boolean isActive;

    public ModelPricing toDomain() {
        return ModelPricing.builder()
                .id(id)
                .providerType(providerType)
                .modelName(modelName)
                .inputPricePerK(inputPricePerK)
                .outputPricePerK(outputPricePerK)
                .cacheHitPricePerK(cacheHitPricePerK)
                .isActive(isActive)
                .build();
    }

    public static ModelPricingPO fromDomain(ModelPricing domain) {
        if (domain == null) return null;
        return ModelPricingPO.builder()
                .id(domain.getId())
                .providerType(domain.getProviderType())
                .modelName(domain.getModelName())
                .inputPricePerK(domain.getInputPricePerK())
                .outputPricePerK(domain.getOutputPricePerK())
                .cacheHitPricePerK(domain.getCacheHitPricePerK())
                .isActive(domain.getIsActive())
                .build();
    }
}
