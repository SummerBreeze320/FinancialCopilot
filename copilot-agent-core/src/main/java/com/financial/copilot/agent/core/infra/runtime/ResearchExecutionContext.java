package com.financial.copilot.agent.core.infra.runtime;

import com.financial.copilot.agent.core.infra.agent.AgentRole;
import com.financial.copilot.agent.core.infra.context.ConversationContextSnapshot;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * <h1>投研运行执行上下文</h1>
 * 贯穿框架 Supervisor 与各专业 Subagent 的执行上下文，封装隔离身份、工作流标识与不可变会话快照。
 *
 * @param identity 执行身份标识
 * @param workflowRunId 工作流单次运行全局唯一 ID
 * @param agentRunId 单次 Agent 角色激活的唯一步骤 ID
 * @param role 当前执行的智能体角色
 * @param conversation 不可变会话快照
 * @param startedAt 运行启动时间戳
 */
public record ResearchExecutionContext(
        ExecutionIdentity identity,
        String workflowRunId,
        String agentRunId,
        AgentRole role,
        ConversationContextSnapshot conversation,
        Instant startedAt
) {
    public ResearchExecutionContext {
        Objects.requireNonNull(identity, "identity cannot be null");
        workflowRunId = requireText(workflowRunId, "workflowRunId");
        agentRunId = requireText(agentRunId, "agentRunId");
        role = role == null ? AgentRole.SUPERVISOR : role;
        Objects.requireNonNull(conversation, "conversation cannot be null");
        startedAt = startedAt == null ? Instant.now() : startedAt;
    }

    /**
     * 构建总控根执行上下文。
     */
    public static ResearchExecutionContext start(
            ExecutionIdentity identity,
            String workflowRunId,
            String agentRunId,
            ConversationContextSnapshot conversation
    ) {
        return new ResearchExecutionContext(
                identity,
                workflowRunId,
                agentRunId,
                AgentRole.SUPERVISOR,
                conversation,
                Instant.now()
        );
    }

    /**
     * 派生分配给特定 Subagent 角色的子执行上下文，分配全新的 agentRunId。
     */
    public ResearchExecutionContext child(AgentRole childRole) {
        Objects.requireNonNull(childRole, "childRole cannot be null");
        return new ResearchExecutionContext(
                this.identity,
                this.workflowRunId,
                UUID.randomUUID().toString(),
                childRole,
                this.conversation,
                this.startedAt
        );
    }

    /**
     * 导出用于框架持久化 Checkpoint Saver 的隔离隔离键 (ThreadKey)。
     */
    public String threadKey() {
        return identity.tenantId() + ":" + identity.userId() + ":" + identity.conversationId() + ":" + workflowRunId;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
