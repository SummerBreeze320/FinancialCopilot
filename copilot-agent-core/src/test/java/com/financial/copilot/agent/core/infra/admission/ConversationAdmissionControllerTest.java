package com.financial.copilot.agent.core.infra.admission;

import com.financial.copilot.agent.core.infra.runtime.ExecutionIdentity;
import com.financial.copilot.common.exception.SystemCapacityExceededException;
import com.financial.copilot.common.exception.UserLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationAdmissionControllerTest {

    private RuntimeCapacityProperties properties;
    private ConversationAdmissionController controller;

    @BeforeEach
    void setUp() {
        properties = new RuntimeCapacityProperties();
        properties.setMaxActiveRunsPerConversation(1);
        properties.setMaxActiveRunsPerUser(2);
        properties.setMaxActiveRunsGlobal(5);
        controller = new ConversationAdmissionController(properties);
    }

    @Test
    void firstRunAcquiresPermitSuccessfully() {
        ExecutionIdentity identity = new ExecutionIdentity(1L, 100L, UUID.randomUUID());
        try (RunAdmission admission = controller.acquire(identity)) {
            assertThat(admission).isNotNull();
            assertThat(admission.identity()).isEqualTo(identity);
            assertThat(admission.isClosed()).isFalse();
        }
    }

    @Test
    void simultaneousSecondRunOnSameConversationThrowsConversationBusyException() {
        UUID conversationId = UUID.randomUUID();
        ExecutionIdentity identity1 = new ExecutionIdentity(1L, 100L, conversationId);
        ExecutionIdentity identity2 = new ExecutionIdentity(1L, 100L, conversationId);

        try (RunAdmission first = controller.acquire(identity1)) {
            assertThatThrownBy(() -> controller.acquire(identity2))
                    .isInstanceOf(ConversationBusyException.class)
                    .hasMessageContaining(conversationId.toString());
        }

        // After first closes, a new run can acquire
        try (RunAdmission second = controller.acquire(identity2)) {
            assertThat(second).isNotNull();
        }
    }

    @Test
    void perUserLimitIsEnforcedAcrossDistinctConversations() {
        Long userId = 101L;
        ExecutionIdentity c1 = new ExecutionIdentity(1L, userId, UUID.randomUUID());
        ExecutionIdentity c2 = new ExecutionIdentity(1L, userId, UUID.randomUUID());
        ExecutionIdentity c3 = new ExecutionIdentity(1L, userId, UUID.randomUUID());

        try (RunAdmission a1 = controller.acquire(c1);
             RunAdmission a2 = controller.acquire(c2)) {
            assertThatThrownBy(() -> controller.acquire(c3))
                    .isInstanceOf(UserLimitExceededException.class);
        }

        // Counters released
        try (RunAdmission a3 = controller.acquire(c3)) {
            assertThat(a3).isNotNull();
        }
    }

    @Test
    void globalCapacityExceededThrowsSystemCapacityExceededException() {
        properties.setMaxActiveRunsGlobal(2);
        properties.setMaxActiveRunsPerUser(5);

        ExecutionIdentity c1 = new ExecutionIdentity(1L, 101L, UUID.randomUUID());
        ExecutionIdentity c2 = new ExecutionIdentity(1L, 102L, UUID.randomUUID());
        ExecutionIdentity c3 = new ExecutionIdentity(1L, 103L, UUID.randomUUID());

        try (RunAdmission a1 = controller.acquire(c1);
             RunAdmission a2 = controller.acquire(c2)) {
            assertThatThrownBy(() -> controller.acquire(c3))
                    .isInstanceOf(SystemCapacityExceededException.class);
        }
    }

    @Test
    void closingAdmissionIsIdempotent() {
        ExecutionIdentity identity = new ExecutionIdentity(1L, 100L, UUID.randomUUID());
        RunAdmission admission = controller.acquire(identity);

        admission.close();
        assertThat(admission.isClosed()).isTrue();
        admission.close(); // No error on double close
        assertThat(admission.isClosed()).isTrue();
    }
}
