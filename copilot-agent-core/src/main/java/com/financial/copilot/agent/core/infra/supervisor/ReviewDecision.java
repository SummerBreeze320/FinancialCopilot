package com.financial.copilot.agent.core.infra.supervisor;

import java.util.List;

/**
 * <h1>ReviewerAgent 审查裁决结构体</h1>
 * 明确约束审查决策行为、目标任务 ID 列表、发现的问题及置信度。
 *
 * @param action 审查动作 (PASS, RETRY_TASK, REPLAN, DEGRADE)
 * @param targetTaskIds 目标重试或变更的任务 ID 列表
 * @param issues 发现的问题或不一致性列表
 * @param requiredEvidence 建议补充的证据或指标 URI 列表
 * @param confidence 审查置信度打分 (0.0 ~ 1.0)
 * @author FinancialCopilot
 */
public record ReviewDecision(
        ReviewAction action,
        List<String> targetTaskIds,
        List<String> issues,
        List<String> requiredEvidence,
        double confidence
) {
    public ReviewDecision {
        targetTaskIds = targetTaskIds != null ? List.copyOf(targetTaskIds) : List.of();
        issues = issues != null ? List.copyOf(issues) : List.of();
        requiredEvidence = requiredEvidence != null ? List.copyOf(requiredEvidence) : List.of();
    }

    public static ReviewDecision pass() {
        return new ReviewDecision(ReviewAction.PASS, List.of(), List.of(), List.of(), 1.0);
    }

    public static ReviewDecision retry(List<String> taskIds, String issue) {
        return new ReviewDecision(ReviewAction.RETRY_TASK, taskIds, List.of(issue), List.of(), 0.8);
    }

    public static ReviewDecision replan(String issue, List<String> requiredEvidence) {
        return new ReviewDecision(ReviewAction.REPLAN, List.of(), List.of(issue), requiredEvidence, 0.7);
    }

    public static ReviewDecision degrade(String reason, List<String> issues) {
        return new ReviewDecision(ReviewAction.DEGRADE, List.of(), issues != null ? issues : List.of(reason), List.of(), 0.5);
    }
}
