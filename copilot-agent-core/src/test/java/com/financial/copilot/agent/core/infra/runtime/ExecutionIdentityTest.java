package com.financial.copilot.agent.core.infra.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionIdentityTest {

    @Test
    void rejectsNullFields() {
        UUID conversationId = UUID.randomUUID();

        assertThatThrownBy(() -> new ExecutionIdentity(null, 7L, conversationId))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tenantId");

        assertThatThrownBy(() -> new ExecutionIdentity(1L, null, conversationId))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userId");

        assertThatThrownBy(() -> new ExecutionIdentity(1L, 7L, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("conversationId");
    }

    @Test
    void acceptsValidFieldsAndDerivesSessionKey() {
        UUID conversationId = UUID.randomUUID();
        ExecutionIdentity identity = new ExecutionIdentity(10L, 7L, conversationId);

        assertThat(identity.tenantId()).isEqualTo(10L);
        assertThat(identity.userId()).isEqualTo(7L);
        assertThat(identity.conversationId()).isEqualTo(conversationId);
        assertThat(identity.sessionKey()).isEqualTo("7:" + conversationId);
    }
}
