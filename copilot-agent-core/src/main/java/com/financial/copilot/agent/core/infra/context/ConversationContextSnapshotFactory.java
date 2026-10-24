package com.financial.copilot.agent.core.infra.context;

import com.financial.copilot.agent.core.infra.context.ConversationContextSnapshot;
import com.financial.copilot.agent.core.infra.runtime.ExecutionIdentity;
import com.financial.copilot.agent.core.platform.conversation.ConversationService;
import com.financial.copilot.agent.core.platform.user.service.UserService;
import com.financial.copilot.domain.platform.user.entity.UserInvestmentProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * <h1>会话上下文不可变快照工厂 (ConversationContextSnapshotFactory)</h1>
 * <p>
 * 在 Run 启动初期执行一次性防御式上下文抓取，获取近期已完成的历史问答、适格画像与记忆摘要，
 * 输出整个执行生命周期内完全不可变的只读快照。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class ConversationContextSnapshotFactory {

    private final ConversationService conversationService;
    private final UserService userService;
    private final int defaultHistoryLimit;

    @Autowired
    public ConversationContextSnapshotFactory(ConversationService conversationService, UserService userService) {
        this(conversationService, userService, 10);
    }

    public ConversationContextSnapshotFactory(ConversationService conversationService,
                                             UserService userService,
                                             int defaultHistoryLimit) {
        this.conversationService = Objects.requireNonNull(conversationService, "conversationService cannot be null");
        this.userService = Objects.requireNonNull(userService, "userService cannot be null");
        this.defaultHistoryLimit = defaultHistoryLimit;
    }

    /**
     * 为当前会话执行上下文创建不可变快照。
     *
     * @param identity 执行身份与会话凭据
     * @param currentQuestion 当前投研提问
     * @return 不可变上下文快照
     */
    public ConversationContextSnapshot create(ExecutionIdentity identity, String currentQuestion) {
        return create(identity, currentQuestion, "");
    }

    /**
     * 为当前会话执行上下文创建包含记忆摘要的不可变快照。
     *
     * @param identity 执行身份与会话凭据
     * @param currentQuestion 当前投研提问
     * @param memorySummary 长期记忆摘要
     * @return 不可变上下文快照
     */
    public ConversationContextSnapshot create(ExecutionIdentity identity, String currentQuestion, String memorySummary) {
        Objects.requireNonNull(identity, "identity cannot be null");
        Objects.requireNonNull(currentQuestion, "currentQuestion cannot be null");

        List<String> messages = conversationService.recentContext(
                identity.userId(), identity.conversationId(), null, defaultHistoryLimit);

        UserInvestmentProfile profile = userService.getInvestmentProfile(identity.userId());

        return ConversationContextSnapshot.of(
                messages,
                profile,
                currentQuestion,
                memorySummary
        );
    }
}
