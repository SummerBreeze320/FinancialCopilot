package com.financial.copilot.agent.core.infra.supervisor.context;

import com.financial.copilot.agent.core.infra.supervisor.runtime.ConversationContextSnapshot;
import com.financial.copilot.agent.core.infra.supervisor.runtime.ExecutionIdentity;
import com.financial.copilot.agent.core.platform.conversation.ConversationService;
import com.financial.copilot.agent.core.platform.user.service.UserService;
import com.financial.copilot.domain.platform.user.entity.UserInvestmentProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationContextSnapshotFactoryTest {

    @Mock
    private ConversationService conversationService;

    @Mock
    private UserService userService;

    private ConversationContextSnapshotFactory factory;
    private ExecutionIdentity identity;

    @BeforeEach
    void setUp() {
        factory = new ConversationContextSnapshotFactory(conversationService, userService);
        identity = new ExecutionIdentity(1L, 100L, UUID.randomUUID());
    }

    @Test
    void createsImmutableSnapshotWithContextAndProfile() {
        when(conversationService.recentContext(eq(100L), eq(identity.conversationId()), any(), eq(10)))
                .thenReturn(List.of("USER: 推荐基金", "ASSISTANT: 易方达蓝筹"));

        UserInvestmentProfile profile = new UserInvestmentProfile();
        when(userService.getInvestmentProfile(100L)).thenReturn(profile);

        ConversationContextSnapshot snapshot = factory.create(identity, "请详细分析易方达蓝筹");

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.currentQuestion()).isEqualTo("请详细分析易方达蓝筹");
        assertThat(snapshot.completedMessages()).containsExactly("USER: 推荐基金", "ASSISTANT: 易方达蓝筹");
        assertThat(snapshot.investmentProfile()).isSameAs(profile);
        assertThat(snapshot.capturedAt()).isNotNull();

        assertThatThrownBy(() -> snapshot.completedMessages().add("new message"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void gracefullyHandlesNullProfileOrEmptyHistory() {
        when(conversationService.recentContext(anyLong(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(userService.getInvestmentProfile(anyLong())).thenReturn(null);

        ConversationContextSnapshot snapshot = factory.create(identity, "首轮提问");

        assertThat(snapshot.completedMessages()).isEmpty();
        assertThat(snapshot.investmentProfile()).isNull();
        assertThat(snapshot.currentQuestion()).isEqualTo("首轮提问");
    }
}
