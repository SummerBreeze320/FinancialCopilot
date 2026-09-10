package com.financial.copilot.agent.core.llm.provider;

import lombok.Getter;

/**
 * <h1>大模型投研性能与思考强度档位枚举 (LLM Performance & Reasoning Level)</h1>
 * <p>
 * 职责：面向金融客户提供直观易懂的业务级“投研深度”调节抽象，
 * 并在底层自动联动大模型推理思考强度（reasoning_effort）、采样温度（temperature）与最大生成 Token 预算（maxTokens）。
 * </p>
 *
 * @author FinancialCopilot
 */
@Getter
public enum LlmPerformanceLevel {

    /**
     * 低（极速初筛模式）：确定性极高，消耗 Token 最低，响应最快。
     * 适合标的代码抽取、意图识别、快速初筛。
     */
    LOW("低 (极速初筛)", "low", 0.0, 2048),

    /**
     * 中（标准投研模式 - 默认）：微创新平衡，适合常规基金/个股多维体检、定期报告观点抽取与双标的对标。
     */
    MIDDLE("中 (标准投研)", "medium", 0.2, 4096),

    /**
     * 高（深度长程推演）：开启最大思考预算与多角度发散归因，适合跨周期大势研判、风格漂移归因与万字 CIO 资产配置研报终审。
     */
    HIGH("高 (深度推演)", "high", 0.4, 8192);

    /**
     * 中文显示名称
     */
    private final String displayName;

    /**
     * 对应推理模型 (如 o1, o3-mini, deepseek-reasoner) 的官方推理预算参数
     */
    private final String reasoningEffort;

    /**
     * 推荐默认采样温度
     */
    private final double defaultTemperature;

    /**
     * 推荐最大生成 Token 数
     */
    private final int defaultMaxTokens;

    LlmPerformanceLevel(String displayName, String reasoningEffort, double defaultTemperature, int defaultMaxTokens) {
        this.displayName = displayName;
        this.reasoningEffort = reasoningEffort;
        this.defaultTemperature = defaultTemperature;
        this.defaultMaxTokens = defaultMaxTokens;
    }

    /**
     * 安全解析字符串为性能档位，若未识别则返回默认的 MIDDLE 档位
     *
     * @param levelStr 档位字符串（忽略大小写，支持 LOW / MIDDLE / HIGH 或 MEDIUM）
     * @return 对应的档位枚举
     */
    public static LlmPerformanceLevel fromString(String levelStr) {
        if (levelStr == null || levelStr.isBlank()) {
            return MIDDLE;
        }
        String clean = levelStr.trim().toUpperCase();
        if ("MEDIUM".equals(clean)) {
            return MIDDLE;
        }
        for (LlmPerformanceLevel level : values()) {
            if (level.name().equalsIgnoreCase(clean)) {
                return level;
            }
        }
        return MIDDLE;
    }
}
