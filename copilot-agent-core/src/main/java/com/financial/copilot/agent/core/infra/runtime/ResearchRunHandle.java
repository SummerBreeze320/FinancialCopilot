package com.financial.copilot.agent.core.infra.runtime;

import com.financial.copilot.common.event.ResearchStreamEvent;
import reactor.core.publisher.Flux;

import java.util.concurrent.CompletableFuture;

/**
 * <h1>投研运行句柄契约</h1>
 * 统一提供响应式事件流、异步完成 Promise 以及取消控制能力，供同步与 SSE 端点复用。
 */
public interface ResearchRunHandle {
    /**
     * 当前运行的工作流唯一标识。
     */
    String workflowRunId();

    /**
     * 绑定的执行身份。
     */
    ExecutionIdentity identity();

    /**
     * 订阅运行过程中的事件流（状态迁移、思考输出、工具调用、报告生成等）。
     */
    Flux<ResearchStreamEvent> events();

    /**
     * 获取异步最终结果。
     */
    CompletableFuture<ResearchRunResult> completion();

    /**
     * 取消当前运行。
     *
     * @param reason 取消原因
     */
    void cancel(String reason);

    static ResearchRunHandle of(
            String workflowRunId,
            ExecutionIdentity identity,
            Flux<ResearchStreamEvent> events,
            CompletableFuture<ResearchRunResult> completion,
            java.util.function.Consumer<String> cancellation
    ) {
        return new SimpleResearchRunHandle(workflowRunId, identity, events, completion, cancellation);
    }

    record SimpleResearchRunHandle(
            String workflowRunId,
            ExecutionIdentity identity,
            Flux<ResearchStreamEvent> events,
            CompletableFuture<ResearchRunResult> completion,
            java.util.function.Consumer<String> cancellation
    ) implements ResearchRunHandle {
        @Override
        public void cancel(String reason) {
            if (cancellation != null) {
                cancellation.accept(reason);
            }
        }
    }
}
