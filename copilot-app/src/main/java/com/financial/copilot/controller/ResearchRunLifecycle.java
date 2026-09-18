package com.financial.copilot.controller;

import com.financial.copilot.agent.core.platform.conversation.ConversationService;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunHandle;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunResult;
import com.financial.copilot.domain.platform.conversation.entity.ConversationRun;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** JSON、SSE 与恢复入口共用的终态持久化观察器。 */
@Component
public class ResearchRunLifecycle {
    private final ConversationService conversations;

    public ResearchRunLifecycle(ConversationService conversations) {
        this.conversations = conversations;
    }

    /** 返回等待消息落库的 future，避免 Graph 完成却向用户误报持久化成功。 */
    public CompletableFuture<GraphRunResult> observe(Long userId, ConversationRun run,
            String sessionKey, String prompt, GraphRunHandle handle) {
        return handle.completion().handle((result, error) -> {
            if (error != null) {
                Throwable cause = error;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof CancellationException) {
                    conversations.cancel(userId, run.runId(), "运行已取消");
                } else {
                    conversations.fail(userId, run.runId(), cause);
                }
                throw new CompletionException(cause);
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
