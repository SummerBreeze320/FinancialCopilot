package com.financial.copilot.agent.core.dag.artifact.payload;

import java.util.List;
import java.util.Map;

/**
 * <h1>终审投研研报产物载荷 (FinalSynthesisReport)</h1>
 * <p>包含研报标题、执行摘要、Markdown 研报全文、资产配置建议权重与免责声明。</p>
 */
public record FinalSynthesisReport(
    String title,
    String summary,
    String markdownReport,
    Map<String, Double> assetAllocations,
    List<String> recommendedFunds,
    String disclaimer
) {
    public FinalSynthesisReport {
        assetAllocations = assetAllocations != null ? Map.copyOf(assetAllocations) : Map.of();
        recommendedFunds = recommendedFunds != null ? List.copyOf(recommendedFunds) : List.of();
    }

    public static FinalSynthesisReport of(String summary, String markdownReport) {
        return new FinalSynthesisReport("专业基金投资配置研报", summary, markdownReport, Map.of(), List.of(), "本报告仅供参考，不构成任何实质性投资要约。市场有风险，投资需谨慎。");
    }

    public static FinalSynthesisReport of(String title, String summary, String markdownReport, Map<String, Double> assetAllocations, List<String> recommendedFunds) {
        return new FinalSynthesisReport(title, summary, markdownReport, assetAllocations, recommendedFunds, "本报告仅供参考，不构成任何实质性投资要约。市场有风险，投资需谨慎。");
    }
}
