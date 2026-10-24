package com.financial.copilot.common.intent;

/**
 * <h1>意图识别命中层级枚举</h1>
 *
 * @author FinancialCopilot
 */
public enum MatchingLayer {
    /** 规则层秒级短路命中 */
    RULE,
    /** 轻量小模型/微调分类器多轮融合命中 */
    SLM,
    /** 大模型语义深度推理与 OOD 兜底命中 */
    LLM_FALLBACK
}
