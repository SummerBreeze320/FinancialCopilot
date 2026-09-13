package com.financial.copilot.controller;

import com.financial.copilot.agent.core.billing.WalletBillingService;
import com.financial.copilot.agent.core.agents.AgentRoleCatalog;
import com.financial.copilot.agent.core.user.service.UserService;
import com.financial.copilot.agent.core.workflow.FinancialResearchWorkflow;
import com.financial.copilot.agent.core.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.dag.runtime.GraphRunResult;
import com.financial.copilot.agent.core.dag.runtime.RunMode;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.common.event.ResearchStreamEvent;
import com.financial.copilot.common.exception.WalletInsufficientException;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.config.security.SecurityUtils;
import com.financial.copilot.agent.core.memory.ShortTermMemoryService;
import com.financial.copilot.agent.core.memory.LongTermMemoryService;
import com.financial.copilot.agent.core.memory.RefinedFact;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.ArrayList;
import java.util.function.Consumer;
import com.financial.copilot.agent.core.llm.dto.LlmResponse;
import reactor.core.scheduler.Schedulers;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <h1>金融多资产智能投研 Agent REST / SSE 控制器</h1>
 * <p>
 * 投研执行统一使用 {@code POST /api/v1/research/runs}，由 Accept 协商同步 JSON 或 SSE 事件流。
 * 另提供运行控制、会话记忆诊断与平台健康探针。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/research")
public class ResearchAgentController {

    /**
     * 投研多智能体工作流总调度器
     */
    private final FinancialResearchWorkflow workflow;

    /**
     * 算力计量计费与账户钱包业务服务
     */
    private final WalletBillingService billingService;

    /**
     * 用户统一管理与画像业务服务
     */
    private final UserService userService;

    /**
     * 短期会话记忆服务 (Redis)
     */
    private final ShortTermMemoryService shortTermMemoryService;

    /**
     * 长期语义记忆与提纯事实服务 (PostgreSQL + Redis)
     */
    private final LongTermMemoryService longTermMemoryService;

    /**
     * 构造函数，自动注入工作流组件、计费中心、用户画像及记忆系统服务
     */
    public ResearchAgentController(FinancialResearchWorkflow workflow,
                                   WalletBillingService billingService,
                                   UserService userService,
                                   ShortTermMemoryService shortTermMemoryService,
                                   LongTermMemoryService longTermMemoryService) {
        this.workflow = workflow;
        this.billingService = billingService;
        this.userService = userService;
        this.shortTermMemoryService = shortTermMemoryService;
        this.longTermMemoryService = longTermMemoryService;
    }

    /**
     * 统一投研运行请求体
     */
    @Data
    public static class ResearchRunRequest {
        /**
         * 用户输入的自然语言投研问题或选基要求
         */
        private String prompt;

        /**
         * 会话唯一标识 (可选，用于串联多轮对话与短期/长期记忆提纯)
         */
        private String sessionId;

        /**
         * 客户端是否开启深度思考推理模式（false: 极速标准投研模式 deepseek-chat, true: 深度推理思考模式 deepseek-reasoner）
         */
        private Boolean enableThinking = false;

    }

    /**
     * 同步投研运行响应体
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResearchRunResponse {
        private String runId;
        private String sessionId;
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

    /**
     * 会话记忆响应体 (包含短期上下文与提纯长期事实)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionMemoryResponse {
        private String sessionId;
        private List<String> shortTermMessages;
        private List<String> longTermEntries;
        private List<RefinedFactDTO> refinedFacts;
        private long timestamp;
    }

    /**
     * 提纯事实 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RefinedFactDTO {
        private Long id;
        private String type;
        private String content;
        private LocalDateTime createdAt;
    }

    /**
     * 动态执行图 SSE 流式交互入口
     */
    @PostMapping(value = "/runs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResearchStreamEvent> streamRun(@RequestBody ResearchRunRequest request) {
        return resolveUserId().publishOn(Schedulers.boundedElastic()).flatMapMany(uid -> {
            StartedRun started = start(uid, request, RunMode.STREAM, usage -> chargeUsage(uid,
                    SecurityUtils.sessionKey(uid, startedSession(request)), usage));
            return started.handle().events();
        });
    }

    /**
     * 同步结构化投研入口
     */
    @PostMapping(value = "/runs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ApiResult<ResearchRunResponse>> run(@RequestBody ResearchRunRequest request) {
        return resolveUserId().publishOn(Schedulers.boundedElastic()).map(uid -> {
            List<LlmResponse> usages = new ArrayList<>();
            String clientSession = startedSession(request);
            String key = SecurityUtils.sessionKey(uid, clientSession);
            StartedRun started = start(uid, request, RunMode.SYNC, usage -> {
                        chargeUsage(uid, key, usage);
                        usages.add(usage);
                    });
            GraphRunResult result = started.handle().completion().join();
            return ApiResult.success(ResearchRunResponse.builder()
                    .runId(result.runId())
                    .sessionId(clientSession).report(report(result))
                    .model(usages.isEmpty() ? null : usages.get(usages.size() - 1).getModel())
                    .promptTokens(usages.stream().mapToInt(LlmResponse::getPromptTokens).sum())
                    .completionTokens(usages.stream().mapToInt(LlmResponse::getCompletionTokens).sum())
                    .totalNodes(result.graph().getNodes().size())
                    .graphRevision(result.graph().getRevision())
                    .summary("ExecutionGraph revision " + result.graph().getRevision())
                    .executionTimeMs(java.time.Duration.between(result.startedAt(), result.completedAt()).toMillis())
                    .timestamp(System.currentTimeMillis()).build());
        });
    }

    private StartedRun start(Long uid, ResearchRunRequest request, RunMode mode, Consumer<LlmResponse> usageConsumer) {
        validatePrompt(request.getPrompt());
        String clientSession = startedSession(request);
        String key = SecurityUtils.sessionKey(uid, clientSession);
        billingService.checkBalance(uid, 100L);
        var handle = workflow.run(new GraphRunRequest(UUID.randomUUID().toString(), uid, key,
                request.getPrompt(), Boolean.TRUE.equals(request.getEnableThinking()),
                userService.getInvestmentProfile(uid), usageConsumer, mode));
        return new StartedRun(clientSession, handle);
    }

    private String startedSession(ResearchRunRequest request) {
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            request.setSessionId(UUID.randomUUID().toString());
        }
        return request.getSessionId();
    }

    private record StartedRun(String sessionId, com.financial.copilot.agent.core.dag.runtime.GraphRunHandle handle) {}

    /**
     * 查询指定会话的记忆诊断与语义提纯事实记录 REST 接口
     *
     * @param sessionId 会话唯一 ID
     * @return 该会话下的短期上下文、长期记忆条目与后台提纯的结构化事实
     */
    @GetMapping("/memory/session/{sessionId}")
    public Mono<ApiResult<SessionMemoryResponse>> getSessionMemory(@PathVariable("sessionId") String sessionId) {
        return resolveUserId().publishOn(Schedulers.boundedElastic()).map(uid -> {
            String key = SecurityUtils.sessionKey(uid, sessionId);
            List<String> shortTerm = shortTermMemoryService.getContext(key);
            List<String> longTerm = longTermMemoryService.retrieve(key, 20);
            List<RefinedFactDTO> facts = longTermMemoryService.getRefinedFacts(key).stream()
                    .map(rf -> RefinedFactDTO.builder()
                            .id(rf.getId())
                            .type(rf.getType())
                            .content(rf.getContent())
                            .createdAt(rf.getCreatedAt())
                            .build())
                    .collect(Collectors.toList());

            SessionMemoryResponse response = SessionMemoryResponse.builder()
                    .sessionId(sessionId)
                    .shortTermMessages(shortTerm != null ? shortTerm : List.of())
                    .longTermEntries(longTerm != null ? longTerm : List.of())
                    .refinedFacts(facts)
                    .timestamp(System.currentTimeMillis())
                    .build();

            return ApiResult.success(response);
        });
    }

    /**
     * 辅助解析当前有效用户 ID
     */
    private void chargeUsage(Long uid, String sessionKey, LlmResponse usage) {
        billingService.deductTokenPoints(uid, sessionKey, "LLM_CALL", usage.getProvider().name(), usage.getModel(),
                usage.getPromptTokens(), usage.getCompletionTokens(), usage.getLatencyMs());
    }

    private String report(GraphRunResult result) {
        return result.artifacts().values().stream()
                .filter(artifact -> artifact.type() == ArtifactType.FINAL_REPORT)
                .map(artifact -> artifact.payload())
                .filter(FinalSynthesisReport.class::isInstance)
                .map(FinalSynthesisReport.class::cast)
                .map(FinalSynthesisReport::markdownReport)
                .findFirst().orElse("");
    }

    private void validatePrompt(String prompt) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 20000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid prompt");
        }
    }

    private Mono<Long> resolveUserId() {
        return SecurityUtils.requireCurrentUserId(null);
    }

    /**
     * 积分不足欠费异常全局捕获处理器 (HTTP 402 Payment Required)
     */
    @ExceptionHandler(WalletInsufficientException.class)
    @ResponseStatus(HttpStatus.PAYMENT_REQUIRED)
    public ApiResult<Map<String, Object>> handleWalletInsufficient(WalletInsufficientException e) {
        log.warn("[WALLET-INSUFFICIENT] 捕获积分欠费异常: {}", e.getMessage());
        return ApiResult.<Map<String, Object>>builder()
                .code(402)
                .message(e.getMessage())
                .data(Map.of(
                        "userId", e.getUserId(),
                        "currentBalance", e.getCurrentBalance() != null ? e.getCurrentBalance() : 0L,
                        "requiredPoints", e.getRequiredPoints()
                ))
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 平台健康检查与多资产能力清单
     *
     * @return 系统运行状态与已挂载资产模块概览
     */
    @GetMapping("/health")
    public ApiResult<Map<String, Object>> healthCheck() {
        return ApiResult.success(Map.of(
                "status", "UP",
                "system", "Financial Research Agent (AgentScope + Spring Boot 3 + MyBatis-Plus)",
                "activeDomain", "FUND (公募基金深度实施)",
                "extensibleDomains", new String[]{"STOCK (股票)", "FUTURES (期货)", "WEALTH (银行理财)"},
                "pipelineCapabilities", AgentRoleCatalog.taskTypes().stream().sorted().toArray(String[]::new),
                "orm", "Lombok + MyBatis-Plus 3.5.7 + PGVector",
                "timestamp", System.currentTimeMillis()
        ));
    }
}
