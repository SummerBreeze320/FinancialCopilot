package com.financial.copilot.agent.core.infra.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * <h1>投研运行最终结果契约</h1>
 *
 * @param workflowRunId 运行全局 ID
 * @param identity 执行身份
 * @param finalReport 最终合成研报 Markdown
 * @param status 终态状态字符串 (SUCCEEDED, FAILED, CANCELLED)
 * @param evidenceReferences 证据追溯索引引用列表
 * @param startedAt 启动时间
 * @param completedAt 结束时间
 */
public record ResearchRunResult(
        String workflowRunId,
        ExecutionIdentity identity,
        String finalReport,
        String status,
        List<String> evidenceReferences,
        Instant startedAt,
        Instant completedAt
) {
    public ResearchRunResult {
        workflowRunId = requireText(workflowRunId, "workflowRunId");
        Objects.requireNonNull(identity, "identity cannot be null");
        finalReport = finalReport == null ? "" : finalReport;
        status = status == null ? "SUCCEEDED" : status;
        evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
        startedAt = startedAt == null ? Instant.now() : startedAt;
        completedAt = completedAt == null ? Instant.now() : completedAt;
    }

    public String runId() {
        return workflowRunId;
    }

    public List<String> evidenceIds() {
        return evidenceReferences;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
