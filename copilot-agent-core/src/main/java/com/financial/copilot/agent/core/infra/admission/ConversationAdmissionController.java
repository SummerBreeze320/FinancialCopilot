package com.financial.copilot.agent.core.infra.admission;

import com.financial.copilot.agent.core.infra.runtime.ExecutionIdentity;
import com.financial.copilot.common.exception.SystemCapacityExceededException;
import com.financial.copilot.common.exception.UserLimitExceededException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <h1>会话准入与并发容量控制器 (ConversationAdmissionController)</h1>
 * <p>
 * 严格保证会话维度的单写串行化，防止相同会话多请求重入扰乱上下文；
 * 同时执行单用户最大活跃 Run 配额限制及全局系统容量限流保护。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class ConversationAdmissionController {

    private final RuntimeCapacityProperties properties;
    private final AtomicInteger globalActiveRuns = new AtomicInteger(0);
    private final ConcurrentHashMap<Long, AtomicInteger> userActiveRuns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> activeConversations = new ConcurrentHashMap<>();

    public ConversationAdmissionController(RuntimeCapacityProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
    }

    /**
     * 尝试为当前执行凭证获取准入许可。
     *
     * @param identity 执行身份与会话上下文
     * @return 准入许可凭证
     * @throws NullPointerException            当 identity 为 null
     * @throws SystemCapacityExceededException 当系统全局并发超限
     * @throws UserLimitExceededException      当单用户并发超限
     * @throws ConversationBusyException       当该会话已有活跃任务正在执行
     */
    public RunAdmission acquire(ExecutionIdentity identity) {
        Objects.requireNonNull(identity, "identity cannot be null");

        // 1. 全局容量校验
        int globalCurrent = globalActiveRuns.incrementAndGet();
        if (globalCurrent > properties.getMaxActiveRunsGlobal()) {
            globalActiveRuns.decrementAndGet();
            throw new SystemCapacityExceededException(
                    "Global active research runs limit reached: " + properties.getMaxActiveRunsGlobal());
        }

        // 2. 单用户并发配额校验
        AtomicInteger userCount = userActiveRuns.computeIfAbsent(identity.userId(), k -> new AtomicInteger(0));
        int userCurrent = userCount.incrementAndGet();
        if (userCurrent > properties.getMaxActiveRunsPerUser()) {
            userCount.decrementAndGet();
            globalActiveRuns.decrementAndGet();
            throw new UserLimitExceededException(identity.userId(),
                    "User active runs limit reached: " + properties.getMaxActiveRunsPerUser());
        }

        // 3. 单会话串行独占校验
        Boolean previous = activeConversations.putIfAbsent(identity.conversationId(), Boolean.TRUE);
        if (previous != null) {
            userCount.decrementAndGet();
            globalActiveRuns.decrementAndGet();
            throw new ConversationBusyException(identity.conversationId(),
                    "Conversation " + identity.conversationId() + " is already running an active research task");
        }

        // 返回包含幂等释放回调的许可
        return new RunAdmission(identity, () -> {
            activeConversations.remove(identity.conversationId());
            userCount.decrementAndGet();
            globalActiveRuns.decrementAndGet();
        });
    }

    /**
     * 获取当前系统全局活跃的 Run 数量。
     */
    public int getGlobalActiveCount() {
        return globalActiveRuns.get();
    }

    /**
     * 获取指定用户的活跃 Run 数量。
     */
    public int getUserActiveCount(Long userId) {
        AtomicInteger count = userActiveRuns.get(userId);
        return count != null ? count.get() : 0;
    }

    /**
     * 检查指定会话是否正在运行任务。
     */
    public boolean isConversationActive(UUID conversationId) {
        return activeConversations.containsKey(conversationId);
    }

    public RuntimeCapacityProperties properties() {
        return this.properties;
    }

    public int getActiveRunCount() {
        return getGlobalActiveCount();
    }

    public int getQueuedRunCount() {
        return 0;
    }
}
