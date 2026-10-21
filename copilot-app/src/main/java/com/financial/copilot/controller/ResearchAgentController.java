package com.financial.copilot.controller;

import com.financial.copilot.agent.core.platform.billing.WalletBillingService;
import com.financial.copilot.agent.core.platform.conversation.ConversationService;
import com.financial.copilot.agent.core.infra.supervisor.admission.ConversationAdmissionController;
import com.financial.copilot.agent.core.infra.supervisor.runtime.ResearchRunHandle;
import com.financial.copilot.agent.core.infra.supervisor.runtime.ResearchRunRequest;
import com.financial.copilot.agent.core.infra.supervisor.runtime.ResearchRunResult;
import com.financial.copilot.agent.core.infra.supervisor.runtime.RunMode;
import com.financial.copilot.gateway.model.LlmResponse;
import com.financial.copilot.agent.core.platform.user.service.UserService;
import com.financial.copilot.agent.core.infra.workflow.FinancialResearchWorkflow;
import com.financial.copilot.common.event.ResearchStreamEvent;
import com.financial.copilot.common.exception.WalletInsufficientException;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.config.security.SecurityUtils;
import com.financial.copilot.domain.platform.conversation.entity.ConversationRun;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.financial.copilot.agent.core.infra.thread.VirtualThreadSchedulers;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** 统一对话研究入口：先保存消息，再启动 Supervisor / Subagents 多智能体编排，终态写入后返回成功。 */
@RestController
@RequestMapping("/api/v1/research")
public class ResearchAgentController {
    private final FinancialResearchWorkflow workflow;
    private final WalletBillingService billingService;
    private final UserService userService;
    private final ConversationService conversations;
    private final ResearchRunLifecycle lifecycle;

    public ResearchAgentController(FinancialResearchWorkflow workflow, WalletBillingService billingService,
            UserService userService, ConversationService conversations, ResearchRunLifecycle lifecycle) {
        this.workflow = workflow;
        this.billingService = billingService;
        this.userService = userService;
        this.conversations = conversations;
        this.lifecycle = lifecycle;
    }

    /** 请求仅接受可选对话 UUID；用户身份只从认证读取。 */
    @Data
    public static class ResearchRunRequest {
        private String prompt;
        private UUID conversationId;
        private Boolean enableThinking = false;
    }

    /** 返回新建或延续的对话、运行标识、Supervisor 成果及事实证据。 */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ResearchRunResponse {
        private String runId;
        private UUID conversationId;
        private String report;
        private String model;
        private String supervisorRole;
        private List<String> evidenceIds;
        @Deprecated
        private int totalNodes;
        @Deprecated
        private int graphRevision;
        private String summary;
        private long executionTimeMs;
        private int promptTokens;
        private int completionTokens;
        private long timestamp;
    }

    /** SSE 终态事件需等候持久化结果，断开时请求取消运行。 */
    @PostMapping(value = "/runs", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResearchStreamEvent> streamRun(@RequestBody ResearchRunRequest request) {
        return SecurityUtils.requireCurrentUserId(null).flatMapMany(uid -> {
            StartedRun started = start(uid, request, RunMode.STREAM);
            return started.handle().events().concatMap(event -> {
                if ("run_completed".equals(event.getType())) {
                    return Mono.fromFuture(started.completion(), true).map(ignored -> event);
                }
                return Mono.just(event);
            }).concatWith(Mono.fromFuture(started.completion(), true).then(Mono.empty()))
                    .onErrorResume(error -> Flux.just(ResearchStreamEvent.runFailed(
                            started.run().runId().toString(), "运行或对话保存失败")))
                    .doOnCancel(() -> started.handle().cancel("SSE client disconnected"));
        });
    }

    /** JSON 使用与流式相同的创建与完成逻辑。 */
    @PostMapping(value = "/runs", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ApiResult<ResearchRunResponse>> run(@RequestBody ResearchRunRequest request) {
        return SecurityUtils.requireCurrentUserId(null)
                .flatMap(uid -> Mono.fromCallable(() -> start(uid, request, RunMode.SYNC))
                        .subscribeOn(VirtualThreadSchedulers.scheduler()))
                .flatMap(started -> Mono.fromFuture(started.completion())
                        .map(result -> ApiResult.success(toResponse(started, result))));
    }

    private ResearchRunResponse toResponse(StartedRun started, ResearchRunResult result) {
        List<LlmResponse> usages = started.usages();
        long executionTimeMs = (result.startedAt() != null && result.completedAt() != null)
                ? Duration.between(result.startedAt(), result.completedAt()).toMillis()
                : 0L;
        return ResearchRunResponse.builder().runId(result.runId())
                .conversationId(started.run().conversationId())
                .report(result.finalReport())
                .model(usages.isEmpty() ? null : usages.getLast().getModel())
                .supervisorRole("research_supervisor")
                .evidenceIds(result.evidenceIds())
                .promptTokens(usages.stream().mapToInt(LlmResponse::getPromptTokens).sum())
                .completionTokens(usages.stream().mapToInt(LlmResponse::getCompletionTokens).sum())
                .totalNodes(1)
                .graphRevision(1)
                .summary("Supervisor research run completed")
                .executionTimeMs(executionTimeMs)
                .timestamp(System.currentTimeMillis()).build();
    }

    private StartedRun start(Long uid, ResearchRunRequest request, RunMode mode) {
        if (request.getPrompt() == null || request.getPrompt().isBlank() || request.getPrompt().length() > 20000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid prompt");
        }
        UUID runId = UUID.randomUUID();
        billingService.reserve(uid, runId.toString(), 100L);
        ConversationRun run = conversations.beginRun(uid, request.getConversationId(), runId, request.getPrompt());
        String key = SecurityUtils.sessionKey(uid, run.conversationId().toString());
        List<LlmResponse> usages = new CopyOnWriteArrayList<>();
        AtomicLong accumulatedActualPoints = new AtomicLong(0L);
        try {
            com.financial.copilot.agent.core.infra.supervisor.runtime.ResearchRunRequest runRequest =
                    new com.financial.copilot.agent.core.infra.supervisor.runtime.ResearchRunRequest(
                            run.runId().toString(),
                            1L,
                            uid,
                            run.conversationId(),
                            run.assistantMessageId(),
                            request.getPrompt(),
                            Boolean.TRUE.equals(request.getEnableThinking()),
                            mode
                    );
            ResearchRunHandle handle = workflow.run(runRequest);
            return new StartedRun(run, handle, lifecycle.observe(uid, run, key, request.getPrompt(), handle, accumulatedActualPoints::get), usages);
        } catch (RuntimeException error) {
            billingService.release(uid, runId.toString());
            conversations.fail(uid, run.runId(), error);
            throw error;
        }
    }

    private record StartedRun(ConversationRun run, ResearchRunHandle handle,
                              CompletableFuture<ResearchRunResult> completion, List<LlmResponse> usages) {}

    /** 积分余额不足时返回充值引导所需信息。 */
    @ExceptionHandler(WalletInsufficientException.class)
    @ResponseStatus(HttpStatus.PAYMENT_REQUIRED)
    public ApiResult<Map<String, Object>> handleWalletInsufficient(WalletInsufficientException e) {
        return ApiResult.<Map<String, Object>>builder().code(402).message(e.getMessage())
                .data(Map.of("userId", e.getUserId(), "currentBalance",
                        e.getCurrentBalance() == null ? 0L : e.getCurrentBalance(), "requiredPoints", e.getRequiredPoints()))
                .timestamp(System.currentTimeMillis()).build();
    }

    /** 返回公开健康状态与已注册的研究角色及容量指标。 */
    @GetMapping("/health")
    public ApiResult<Map<String, Object>> healthCheck() {
        ConversationAdmissionController admission = workflow.getAdmissionController();
        Map<String, Object> capacity = Map.of(
                "activeRuns", admission != null ? admission.getActiveRunCount() : 0,
                "queuedRuns", admission != null ? admission.getQueuedRunCount() : 0,
                "maxActiveRuns", admission != null && admission.properties() != null ? admission.properties().getMaxActiveRuns() : 0,
                "maxQueuedRuns", admission != null && admission.properties() != null ? admission.properties().getMaxQueuedRuns() : 0,
                "maxLlmConcurrency", admission != null && admission.properties() != null ? admission.properties().getMaxLlmConcurrency() : 0
        );

        return ApiResult.success(Map.of(
                "status", "UP",
                "system", "Financial Research Agent",
                "activeDomain", "FUND",
                "extensibleDomains", new String[]{"STOCK", "FUTURES", "WEALTH"},
                "pipelineCapabilities", new String[]{"fund_research_agent", "market_research_agent", "report_agent", "risk_review_agent", "stock_research_agent"},
                "orm", "Lombok + MyBatis-Plus 3.5.7 + PGVector",
                "capacity", capacity,
                "timestamp", System.currentTimeMillis()
        ));
    }
}
