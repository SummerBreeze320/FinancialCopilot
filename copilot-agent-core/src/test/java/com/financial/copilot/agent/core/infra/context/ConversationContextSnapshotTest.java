package com.financial.copilot.agent.core.infra.context;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationContextSnapshotTest {

    @Test
    void contextSnapshotDefensivelyCopiesMessages() {
        List<String> messages = new ArrayList<>(List.of("USER: first"));
        ConversationContextSnapshot snapshot = new ConversationContextSnapshot(
                messages, null, "next", Instant.now());
        messages.add("ASSISTANT: mutated");

        assertThat(snapshot.completedMessages()).containsExactly("USER: first");
        assertThatThrownBy(() -> snapshot.completedMessages().add("new message"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void handlesNullInputsGracefully() {
        ConversationContextSnapshot snapshot = new ConversationContextSnapshot(
                null, null, null, null, null);

        assertThat(snapshot.completedMessages()).isEmpty();
        assertThat(snapshot.investmentProfile()).isNull();
        assertThat(snapshot.currentQuestion()).isEqualTo("");
        assertThat(snapshot.memorySummary()).isEqualTo("");
        assertThat(snapshot.capturedAt()).isNotNull();
    }
}
