package com.financial.copilot.data.billing.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financial.copilot.domain.billing.entity.ModelPricing;
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

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String providerType;

    private String modelName;

    private BigDecimal inputPricePerK;

    private BigDecimal outputPricePerK;

    private BigDecimal cacheHitPricePerK;

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
