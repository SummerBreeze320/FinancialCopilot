package com.financial.copilot.agent.core.infra.dag.runtime;

import com.financial.copilot.agent.core.infra.context.ConversationContextSnapshot;
import com.financial.copilot.agent.core.infra.context.ExecutionIdentity;
import com.financial.copilot.gateway.model.LlmResponse;
import com.financial.copilot.domain.platform.user.entity.UserInvestmentProfile;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 工作流执行请求，携带不可变执行身份与上下文快照。
 */
public record GraphRunRequest(
        ExecutionIdentity identity,
        ConversationContextSnapshot snapshot,
        Long assistantMessageId,
        boolean enableThinking,
        Consumer<LlmResponse> usageConsumer,
        RunMode mode,
        java.util.function.Supplier<ConversationContextSnapshot> snapshotSupplier
) {
    public GraphRunRequest {
        Objects.requireNonNull(identity, "identity cannot be null");
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        mode = mode == null ? RunMode.SYNC : mode;
    }

    public GraphRunRequest(
            ExecutionIdentity identity,
            ConversationContextSnapshot snapshot,
            Long assistantMessageId,
            boolean enableThinking,
            Consumer<LlmResponse> usageConsumer,
            RunMode mode
    ) {
        this(identity, snapshot, assistantMessageId, enableThinking, usageConsumer, mode, null);
    }

    public GraphRunRequest resolveSnapshot() {
        if (snapshotSupplier != null) {
            ConversationContextSnapshot fresh = snapshotSupplier.get();
            if (fresh != null) {
                return new GraphRunRequest(identity, fresh, assistantMessageId, enableThinking, usageConsumer, mode, snapshotSupplier);
            }
        }
        return this;
    }

    /** 兼容历史构造器 */
    public GraphRunRequest(
            String runId,
            Long userId,
            UUID conversationId,
            Long assistantMessageId,
            String sessionKey,
            String prompt,
            boolean enableThinking,
            UserInvestmentProfile profile,
            Consumer<LlmResponse> usageConsumer,
            RunMode mode
    ) {
        this(
                new ExecutionIdentity("DEFAULT", userId, conversationId, runId, sessionKey),
                new ConversationContextSnapshot(List.of(), profile, prompt, Instant.now()),
                assistantMessageId,
                enableThinking,
                usageConsumer,
                mode
        );
    }

    public String tenantId() {
        return identity != null ? identity.tenantId() : "DEFAULT";
    }

    public String runId() {
        return identity != null ? identity.workflowRunId() : null;
    }

    public Long userId() {
        return identity != null ? identity.userId() : null;
    }


    public UUID conversationId() {
        return identity != null ? identity.conversationId() : null;
    }

    public String sessionKey() {
        return identity != null ? identity.sessionKey() : null;
    }

    public String prompt() {
        return snapshot != null ? snapshot.currentPrompt() : null;
    }

    public UserInvestmentProfile profile() {
        return snapshot != null ? snapshot.profile() : null;
    }
}
