package com.financial.copilot.agent.core.infra.context;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionIdentityTest {

    @Test
    void identityRejectsBlankServerOwnedKeys() {
        assertThatThrownBy(() -> new ExecutionIdentity("DEFAULT", 7L, UUID.randomUUID(), "", "session"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ExecutionIdentity("", 7L, UUID.randomUUID(), "wf-1", "session"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ExecutionIdentity("DEFAULT", 7L, UUID.randomUUID(), "wf-1", "   "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ExecutionIdentity("DEFAULT", null, UUID.randomUUID(), "wf-1", "session"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ExecutionIdentity("DEFAULT", 7L, null, "wf-1", "session"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void identityAcceptsValidFields() {
        UUID conversationId = UUID.randomUUID();
        ExecutionIdentity identity = new ExecutionIdentity("DEFAULT", 7L, conversationId, "wf-1", "session-key");

        assertThat(identity.tenantId()).isEqualTo("DEFAULT");
        assertThat(identity.userId()).isEqualTo(7L);
        assertThat(identity.conversationId()).isEqualTo(conversationId);
        assertThat(identity.workflowRunId()).isEqualTo("wf-1");
        assertThat(identity.sessionKey()).isEqualTo("session-key");
    }

    @Test
    void contextSnapshotDefensivelyCopiesMessages() {
        List<String> messages = new ArrayList<>(List.of("USER: first"));
        ConversationContextSnapshot snapshot = new ConversationContextSnapshot(
                messages, null, "next", Instant.now());
        messages.add("ASSISTANT: mutated");
        assertThat(snapshot.completedMessages()).containsExactly("USER: first");
    }
}
