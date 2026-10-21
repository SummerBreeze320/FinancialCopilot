package com.financial.copilot.agent.core.infra.supervisor.runtime;

import com.financial.copilot.domain.platform.user.entity.UserInvestmentProfile;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * <h1>不可变会话上下文快照</h1>
 * 在单次运行启动时由服务端一次性捕获生成，防御性深拷贝历史消息，整个 Run 生命周期中只读不变。
 *
 * @param completedMessages 已完成的历史上下文消息列表
 * @param investmentProfile 用户合规适格画像
 * @param currentQuestion 当前投研提问 Prompt
 * @param memorySummary 检索注入的长期记忆摘要
 * @param capturedAt 快照创建时间
 */
public record ConversationContextSnapshot(
        List<String> completedMessages,
        UserInvestmentProfile investmentProfile,
        String currentQuestion,
        String memorySummary,
        Instant capturedAt
) {
    public ConversationContextSnapshot {
        completedMessages = completedMessages == null ? List.of() : List.copyOf(completedMessages);
        currentQuestion = currentQuestion == null ? "" : currentQuestion.strip();
        memorySummary = memorySummary == null ? "" : memorySummary.strip();
        capturedAt = capturedAt == null ? Instant.now() : capturedAt;
    }

    public static ConversationContextSnapshot of(
            List<String> completedMessages,
            UserInvestmentProfile investmentProfile,
            String currentQuestion,
            String memorySummary
    ) {
        return new ConversationContextSnapshot(
                completedMessages,
                investmentProfile,
                currentQuestion,
                memorySummary,
                Instant.now()
        );
    }
}
