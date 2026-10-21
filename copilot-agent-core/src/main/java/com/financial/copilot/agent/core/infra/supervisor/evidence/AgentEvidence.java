package com.financial.copilot.agent.core.infra.supervisor.evidence;

import com.financial.copilot.agent.core.infra.supervisor.agent.AgentRole;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * <h1>不可变金融事实与工具执行证据项 (AgentEvidence)</h1>
 * <p>
 * 每个专业 Subagent 在调用确定性工具后，向所属 Run 的 Evidence 账本追加结构化事实凭证，
 * 用于后续 Supervisor 综合、风控质检及报告合成，模型文字不得替代此类数值事实。
 * </p>
 *
 * @param evidenceId 证据全局唯一 ID
 * @param role 产出该证据的 Agent 角色
 * @param agentRunId 单次 Agent 执行唯一 ID
 * @param toolName 产生证据的确定性工具名称
 * @param summary 面向模型的脱敏紧凑摘要
 * @param structuredPayload 结构化事实数据载荷
 * @param createdAt 产生时间戳
 */
public record AgentEvidence(
        String evidenceId,
        AgentRole role,
        String agentRunId,
        String toolName,
        String summary,
        Object structuredPayload,
        Instant createdAt
) {
    public AgentEvidence {
        evidenceId = (evidenceId == null || evidenceId.isBlank()) ? UUID.randomUUID().toString() : evidenceId;
        Objects.requireNonNull(role, "role cannot be null");
        Objects.requireNonNull(agentRunId, "agentRunId cannot be null");
        Objects.requireNonNull(toolName, "toolName cannot be null");
        summary = summary == null ? "" : summary.strip();
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public static AgentEvidence of(AgentRole role, String agentRunId, String toolName, String summary, Object payload) {
        return new AgentEvidence(
                UUID.randomUUID().toString(),
                role,
                agentRunId,
                toolName,
                summary,
                payload,
                Instant.now()
        );
    }
}
