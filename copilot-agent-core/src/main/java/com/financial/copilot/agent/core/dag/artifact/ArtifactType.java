package com.financial.copilot.agent.core.dag.artifact;

/**
 * <h1>多模态强类型投研产物类型枚举</h1>
 */
public enum ArtifactType {
    /** 初筛基金标的池 */
    FUND_POOL,
    /** 宏观流动性事实与基准观点 */
    MACRO_FACTS,
    /** 单基金/经理多维体检与定量指标分析 */
    FUND_RESEARCH,
    /** 决赛圈多标的深度对标报告 */
    COMPARISON_REPORT,
    /** 招募说明书/定期报告证据碎片 */
    DOCUMENT_EVIDENCE,
    /** 终审合成投研研报与配置建议 */
    FINAL_REPORT,
    /** 通用结构化产物 */
    GENERAL
}
