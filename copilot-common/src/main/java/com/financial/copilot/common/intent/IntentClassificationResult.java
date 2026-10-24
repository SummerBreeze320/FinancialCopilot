package com.financial.copilot.common.intent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * <h1>意图分类与槽位解析结果载荷</h1>
 *
 * @param intent              识别出的意图类型
 * @param confidence          置信度打分 (0.0 ~ 1.0)
 * @param slots               提取出的业务实体槽位映射表
 * @param matchedLayer        命中层级 (RULE / SLM / LLM_FALLBACK)
 * @param isOutOfDomain       是否属于领域外拒识
 * @param clarificationPrompt 引导澄清提示语 (当 OOD 或置信度偏低时提供)
 * @author FinancialCopilot
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntentClassificationResult(
        IntentType intent,
        double confidence,
        Map<String, Object> slots,
        MatchingLayer matchedLayer,
        boolean isOutOfDomain,
        String clarificationPrompt
) {
    public IntentClassificationResult {
        slots = slots != null ? slots : Map.of();
    }

    public static IntentClassificationResult ruleHit(IntentType intent, Map<String, Object> slots) {
        return new IntentClassificationResult(intent, 1.0, slots, MatchingLayer.RULE, false, null);
    }

    public static IntentClassificationResult slmHit(IntentType intent, double confidence, Map<String, Object> slots) {
        return new IntentClassificationResult(intent, confidence, slots, MatchingLayer.SLM, false, null);
    }

    public static IntentClassificationResult ood(String clarificationPrompt) {
        return new IntentClassificationResult(
                IntentType.OUT_OF_DOMAIN,
                0.99,
                Map.of(),
                MatchingLayer.LLM_FALLBACK,
                true,
                clarificationPrompt
        );
    }
}
