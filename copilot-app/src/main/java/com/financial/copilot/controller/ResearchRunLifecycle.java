package com.financial.copilot.controller;

import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunHandle;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunResult;
import com.financial.copilot.agent.core.platform.billing.WalletBillingService;
import com.financial.copilot.agent.core.platform.conversation.ConversationService;
import com.financial.copilot.domain.platform.conversation.entity.ConversationRun;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;

/**
 * <h1>金融投研运行生命周期观察器 (ResearchRunLifecycle)</h1>
 * <p>
 * JSON、SSE 与恢复入口共用的终态持久化观察器，负责运行成功结算、异常/取消释放预占额度与消息入库。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class ResearchRunLifecycle {

    private final ConversationService conversations;
    private final WalletBillingService billing;

    @Autowired
    public ResearchRunLifecycle(ConversationService conversations,
                                @Autowired(required = false) WalletBillingService billing) {
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.billing = billing;
    }

    public ResearchRunLifecycle(ConversationService conversations) {
        this(conversations, null);
    }

    /**
     * 观察运行执行并在终态进行消息落库与算力扣减结算。
     */
    public CompletableFuture<GraphRunResult> observe(Long userId, ConversationRun run,
                                                     String sessionKey, String prompt,
                                                     GraphRunHandle handle) {
        return observe(userId, run, sessionKey, prompt, handle, () -> 0L);
    }

    /**
     * 观察运行执行并在终态进行消息落库与算力实际消耗结算。
     *
     * @param userId 用户 ID
     * @param run 投研运行实体
     * @param sessionKey 会话隔离密钥
     * @param prompt 投研提示词
     * @param handle 运行句柄
     * @param actualPointsSupplier 实际发生消耗积分提供者
     * @return 运行结果异步 Future
     */
    public CompletableFuture<GraphRunResult> observe(Long userId, ConversationRun run,
                                                     String sessionKey, String prompt,
                                                     GraphRunHandle handle,
                                                     LongSupplier actualPointsSupplier) {
        return handle.completion().handle((result, error) -> {
            if (error != null) {
                Throwable cause = error;
                while (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof CancellationException) {
                    conversations.cancel(userId, run.runId(), "运行已取消");
                } else {
                    conversations.fail(userId, run.runId(), cause);
                }
                if (billing != null) {
                    billing.release(userId, run.runId().toString());
                }
                throw new CompletionException(cause);
            }
            if (billing != null) {
                long actualPoints = actualPointsSupplier != null ? actualPointsSupplier.getAsLong() : 0L;
                billing.settle(userId, run.runId().toString(), actualPoints);
                billing.release(userId, run.runId().toString());
            }
            conversations.complete(userId, run, sessionKey, prompt, report(result),
                    Map.of("graphRevision", result.graph().getRevision(),
                            "artifactIds", result.artifacts().values().stream().map(a -> a.id()).toList()));
            return result;
        });
    }

    /** 提取最终用户可见报告，不保存 Agent 的中间推理。 */
    public static String report(GraphRunResult result) {
        return result.artifacts().values().stream().filter(a -> a.type() == ArtifactType.FINAL_REPORT)
                .map(a -> a.payload()).filter(FinalSynthesisReport.class::isInstance)
                .map(FinalSynthesisReport.class::cast).map(FinalSynthesisReport::markdownReport).findFirst().orElse("");
    }
}
