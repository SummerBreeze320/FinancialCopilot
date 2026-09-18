package com.financial.copilot.controller;

import com.financial.copilot.agent.core.business.agents.AgentRoleCatalog;
import com.financial.copilot.agent.core.platform.billing.WalletBillingService;
import com.financial.copilot.agent.core.platform.conversation.ConversationService;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunHandle;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunResult;
import com.financial.copilot.agent.core.infra.dag.runtime.RunMode;
import com.financial.copilot.agent.core.infra.llm.dto.LlmResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/** 统一对话研究入口：先保存消息，再启动 Graph，终态写入后返回成功。 */
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

    /** 返回新建或延续的对话、运行标识及最终报告。 */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ResearchRunResponse {
        private String runId;
        private UUID conversationId;
        private String report;
        private String model;
        private int totalNodes;
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
        return SecurityUtils.requireCurrentUserId(null).publishOn(Schedulers.boundedElastic()).flatMapMany(uid -> {
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
        return SecurityUtils.requireCurrentUserId(null).publishOn(Schedulers.boundedElastic()).map(uid -> {
            StartedRun started = start(uid, request, RunMode.SYNC);
            GraphRunResult result = started.completion().join();
            List<LlmResponse> usages = started.usages();
            return ApiResult.success(ResearchRunResponse.builder().runId(result.runId())
                    .conversationId(started.run().conversationId()).report(ResearchRunLifecycle.report(result))
                    .model(usages.isEmpty() ? null : usages.getLast().getModel())
                    .promptTokens(usages.stream().mapToInt(LlmResponse::getPromptTokens).sum())
                    .completionTokens(usages.stream().mapToInt(LlmResponse::getCompletionTokens).sum())
                    .totalNodes(result.graph().getNodes().size()).graphRevision(result.graph().getRevision())
                    .summary("ExecutionGraph revision " + result.graph().getRevision())
                    .executionTimeMs(Duration.between(result.startedAt(), result.completedAt()).toMillis())
                    .timestamp(System.currentTimeMillis()).build());
        });
    }

    private StartedRun start(Long uid, ResearchRunRequest request, RunMode mode) {
        if (request.getPrompt() == null || request.getPrompt().isBlank() || request.getPrompt().length() > 20000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid prompt");
        }
        billingService.checkBalance(uid, 100L);
        ConversationRun run = conversations.beginRun(uid, request.getConversationId(), UUID.randomUUID(), request.getPrompt());
        String key = SecurityUtils.sessionKey(uid, run.conversationId().toString());
        List<LlmResponse> usages = new CopyOnWriteArrayList<>();
        try {
            conversations.recentContext(uid, run.conversationId(), key, 20);
            GraphRunHandle handle = workflow.run(new GraphRunRequest(run.runId().toString(), uid, run.conversationId(),
                    run.assistantMessageId(), key, request.getPrompt(), Boolean.TRUE.equals(request.getEnableThinking()),
                    userService.getInvestmentProfile(uid), usage -> {
                        billingService.deductTokenPoints(uid, key, "LLM_CALL", usage.getProvider().name(), usage.getModel(),
                                usage.getPromptTokens(), usage.getCompletionTokens(), usage.getLatencyMs());
                        usages.add(usage);
                    }, mode));
            return new StartedRun(run, handle, lifecycle.observe(uid, run, key, request.getPrompt(), handle), usages);
        } catch (RuntimeException error) {
            conversations.fail(uid, run.runId(), error);
            throw error;
        }
    }

    private record StartedRun(ConversationRun run, GraphRunHandle handle,
                              CompletableFuture<GraphRunResult> completion, List<LlmResponse> usages) {}

    /** 积分余额不足时返回充值引导所需信息。 */
    @ExceptionHandler(WalletInsufficientException.class)
    @ResponseStatus(HttpStatus.PAYMENT_REQUIRED)
    public ApiResult<Map<String, Object>> handleWalletInsufficient(WalletInsufficientException e) {
        return ApiResult.<Map<String, Object>>builder().code(402).message(e.getMessage())
                .data(Map.of("userId", e.getUserId(), "currentBalance",
                        e.getCurrentBalance() == null ? 0L : e.getCurrentBalance(), "requiredPoints", e.getRequiredPoints()))
                .timestamp(System.currentTimeMillis()).build();
    }

    /** 返回公开健康状态与已注册的研究角色。 */
    @GetMapping("/health")
    public ApiResult<Map<String, Object>> healthCheck() {
        return ApiResult.success(Map.of("status", "UP", "system", "Financial Research Agent",
                "activeDomain", "FUND", "extensibleDomains", new String[]{"STOCK", "FUTURES", "WEALTH"},
                "pipelineCapabilities", AgentRoleCatalog.taskTypes().stream().sorted().toArray(String[]::new),
                "orm", "Lombok + MyBatis-Plus 3.5.7 + PGVector", "timestamp", System.currentTimeMillis()));
    }
}
