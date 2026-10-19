package com.financial.copilot.agent.core.infra.context;

import com.financial.copilot.domain.platform.user.entity.UserInvestmentProfile;

import java.time.Instant;
import java.util.List;

/**
 * 单次运行开始时的不可变会话上下文快照，防御性拷贝历史消息与用户适格画像。
 */
public record ConversationContextSnapshot(
        List<String> completedMessages,
        UserInvestmentProfile profile,
        String currentPrompt,
        Instant capturedAt
) {
    public ConversationContextSnapshot {
        completedMessages = completedMessages == null ? List.of() : List.copyOf(completedMessages);
        currentPrompt = currentPrompt == null ? "" : currentPrompt;
        capturedAt = capturedAt == null ? Instant.now() : capturedAt;
    }
}
