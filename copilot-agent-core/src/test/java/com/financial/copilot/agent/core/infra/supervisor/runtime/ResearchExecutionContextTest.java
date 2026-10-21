package com.financial.copilot.agent.core.infra.supervisor.runtime;

import com.financial.copilot.agent.core.infra.supervisor.agent.AgentRole;
import com.financial.copilot.domain.platform.user.entity.UserInvestmentProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchExecutionContextTest {

    private UUID conversationId;
    private ExecutionIdentity identity;
    private ConversationContextSnapshot snapshot;

    @BeforeEach
    void setUp() {
        conversationId = UUID.randomUUID();
        identity = new ExecutionIdentity(7L, 11L, conversationId);
        snapshot = ConversationContextSnapshot.of(
                List.of("user: 你好", "assistant: 您好"),
                new UserInvestmentProfile(),
                "请推荐量化基金",
                "关注低回撤策略"
        );
    }

    @Test
    void threadKeyContainsEveryIsolationBoundary() {
        ResearchExecutionContext context = ResearchExecutionContext.start(
                identity, "run-1", "agent-1", snapshot);
        assertThat(context.threadKey()).isEqualTo("7:11:" + conversationId + ":run-1");
    }

    @Test
    void rejectsBlankWorkflowRunId() {
        assertThatThrownBy(() -> ResearchExecutionContext.start(identity, " ", "agent-1", snapshot))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullIdentity() {
        assertThatThrownBy(() -> ResearchExecutionContext.start(null, "run-1", "agent-1", snapshot))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void childAllocatesNewAgentRunIdWhilePreservingBoundaries() {
        ResearchExecutionContext parent = ResearchExecutionContext.start(
                identity, "run-1", "agent-1", snapshot);
        ResearchExecutionContext child = parent.child(AgentRole.FUND_RESEARCH);

        assertThat(child.identity()).isEqualTo(parent.identity());
        assertThat(child.workflowRunId()).isEqualTo(parent.workflowRunId());
        assertThat(child.agentRunId()).isNotEqualTo(parent.agentRunId());
        assertThat(child.role()).isEqualTo(AgentRole.FUND_RESEARCH);
        assertThat(child.startedAt()).isEqualTo(parent.startedAt());
        assertThat(child.conversation()).isSameAs(parent.conversation());
    }

    @Test
    void executionIdentityRejectsNullComponents() {
        assertThatThrownBy(() -> new ExecutionIdentity(null, 11L, conversationId))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExecutionIdentity(7L, null, conversationId))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExecutionIdentity(7L, 11L, null))
                .isInstanceOf(NullPointerException.class);
    }
}
