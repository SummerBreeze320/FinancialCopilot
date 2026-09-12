package com.financial.copilot.agent.core.dag.artifact;

import java.util.List;

/**
 * <h1>金融研报证据契约 (Evidence Contract)</h1>
 * 明确约束结论来源、已引用事实、核心假设、缺失数据与置信度。
 */
public record EvidenceContract(
    String conclusion,              // 投研核心结论
    List<String> evidenceUris,      // 支撑该结论的强类型产物或指标 URI
    List<String> assumptions,       // 核心前提与假设
    List<String> missingEvidence,   // 显式声明的缺失证据项
    double confidence               // 结论置信度打分 (0.0 ~ 1.0)
) {
    public EvidenceContract {
        evidenceUris = evidenceUris != null ? List.copyOf(evidenceUris) : List.of();
        assumptions = assumptions != null ? List.copyOf(assumptions) : List.of();
        missingEvidence = missingEvidence != null ? List.copyOf(missingEvidence) : List.of();
    }

    public static EvidenceContract empty() {
        return new EvidenceContract("", List.of(), List.of(), List.of(), 1.0);
    }

    public boolean isSufficient() {
        return missingEvidence.isEmpty() && confidence >= 0.75;
    }
}
