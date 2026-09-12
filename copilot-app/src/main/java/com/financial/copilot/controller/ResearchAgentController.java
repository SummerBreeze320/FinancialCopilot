package com.financial.copilot.controller;

import com.financial.copilot.agent.core.billing.WalletBillingService;
import com.financial.copilot.agent.core.user.service.UserService;
import com.financial.copilot.agent.core.workflow.FinancialResearchWorkflow;
import com.financial.copilot.common.event.ResearchStreamEvent;
import com.financial.copilot.common.exception.WalletInsufficientException;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.config.security.SecurityUtils;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;
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
 * 提供多端交互接入端点：
 * 1. 阶段式复合流水线 SSE 流式输出接口（包含执行计划、步骤通知、Markdown 研报增量与完结信号）；
 * 2. 同步阻塞式研报生成接口（适合批处理或一次性拉取）；
 * 3. 结构化工作流触发执行接口（返回生成研报、会话唯一标识及阶段执行指标）；
 * 4. 会话记忆诊断与语义提纯事实查询接口（观测短期对话缓存与后台异步提纯的长期记忆）；
 * 5. 平台健康度与资产能力矩阵探针。
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
     * 同步问答分析请求体传输对象
     */
    @Data
    public static class ChatRequest {
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

        /**
         * 兼容字段，仅允许与当前登录用户 ID 相同
         */
        private Long userId;
    }

    /**
     * 结构化工作流触发请求体
     */
    @Data
    public static class WorkflowExecuteRequest {
        /**
         * 用户输入的自然语言投研问题或复合诉求
         */
        private String prompt;

        /**
         * 会话唯一标识 (可选，留空则自动生成 UUID)
         */
        private String sessionId;

        /**
         * 客户端是否开启深度思考推理模式
         */
        private Boolean enableThinking = false;

        /**
         * 客户用户系统唯一 ID (可选，默认为当前登录用户)
         */
        private Long userId;
    }

    /**
     * 结构化工作流响应体
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkflowExecuteResponse {
        private String sessionId;
        private String report;
        private String model;
        private int totalSteps;
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
     * 阶段式复合流水线 SSE 流式交互接口
     *
     * @param prompt         用户自然语言诉求
     * @param sessionId      会话唯一 ID (可选)
     * @param enableThinking 是否开启深度思考推理模式
     * @param userId         用户 ID (可选，若不传则优先取当前已认证的 UID)
     * @return 响应式事件流
     */
    @GetMapping(value = "/chat/pipeline/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResearchStreamEvent> streamPipelineChat(
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "enableThinking", defaultValue = "false") Boolean enableThinking,
            @RequestParam(value = "userId", required = false) Long userId) {
        return resolveUserId(userId).publishOn(Schedulers.boundedElastic()).flatMapMany(uid -> {
            validatePrompt(prompt);
            String clientSession = sessionId == null ? UUID.randomUUID().toString() : sessionId;
            String key = SecurityUtils.sessionKey(uid, clientSession);
            billingService.checkBalance(uid, 100L);
            return workflow.executePipelineStream(key, prompt, Boolean.TRUE.equals(enableThinking),
                    userService.getInvestmentProfile(uid), usage -> chargeUsage(uid, key, usage));
        });
    }

    /**
     * 同步全量投研研报生成接口
     *
     * @param request 请求体封装（含投研问题、是否开启深度思考与用户 ID）
     * @return 最终研报 Markdown 结果
     */
    @PostMapping("/chat")
    public Mono<ApiResult<String>> syncChat(@RequestBody ChatRequest request) {
        return resolveUserId(request.getUserId()).publishOn(Schedulers.boundedElastic()).map(uid -> {
            validatePrompt(request.getPrompt());
            String clientSession = request.getSessionId() == null ? UUID.randomUUID().toString() : request.getSessionId();
            String key = SecurityUtils.sessionKey(uid, clientSession);
            billingService.checkBalance(uid, 100L);
            var result = workflow.executeWithResult(key, request.getPrompt(), Boolean.TRUE.equals(request.getEnableThinking()),
                    userService.getInvestmentProfile(uid), usage -> chargeUsage(uid, key, usage));
            return ApiResult.success(result.getReport());
        });
    }

    /**
     * 触发工作流执行并返回结构化研报结果 REST 接口
     * <p>
     * 1. 执行任务规划与多智能体拓扑协作；
     * 2. 生成专业投研 Markdown 报告并沉淀短期/长期记忆；
     * 3. 异步触发 LLM 对本轮对话进行语义提纯；
     * 4. 返回包含会话 ID、研报内容、执行耗时与步骤清单的结构化结果。
     * </p>
     *
     * @param request 工作流触发请求
     * @return 结构化工作流执行结果
     */
    @PostMapping("/workflow/execute")
    public Mono<ApiResult<WorkflowExecuteResponse>> executeWorkflow(@RequestBody WorkflowExecuteRequest request) {
        return resolveUserId(request.getUserId()).publishOn(Schedulers.boundedElastic()).map(uid -> {
            validatePrompt(request.getPrompt());
            String clientSession = request.getSessionId() == null ? UUID.randomUUID().toString() : request.getSessionId();
            String key = SecurityUtils.sessionKey(uid, clientSession);
            billingService.checkBalance(uid, 100L);
            List<LlmResponse> usages = new ArrayList<>();
            var result = workflow.executeWithResult(key, request.getPrompt(), Boolean.TRUE.equals(request.getEnableThinking()),
                    userService.getInvestmentProfile(uid), usage -> {
                        chargeUsage(uid, key, usage);
                        usages.add(usage);
                    });
            return ApiResult.success(WorkflowExecuteResponse.builder()
                    .sessionId(clientSession).report(result.getReport())
                    .model(usages.isEmpty() ? null : usages.get(usages.size() - 1).getModel())
                    .promptTokens(usages.stream().mapToInt(LlmResponse::getPromptTokens).sum())
                    .completionTokens(usages.stream().mapToInt(LlmResponse::getCompletionTokens).sum())
                    .totalSteps(result.getPlan() == null ? 0 : result.getPlan().getSteps().size())
                    .summary(result.getPlan() == null ? "" : result.getPlan().getSummary())
                    .executionTimeMs(result.getDurationMs()).timestamp(System.currentTimeMillis()).build());
        });
    }

    /**
     * 查询指定会话的记忆诊断与语义提纯事实记录 REST 接口
     *
     * @param sessionId 会话唯一 ID
     * @return 该会话下的短期上下文、长期记忆条目与后台提纯的结构化事实
     */
    @GetMapping("/memory/session/{sessionId}")
    public Mono<ApiResult<SessionMemoryResponse>> getSessionMemory(@PathVariable("sessionId") String sessionId) {
        return resolveUserId(null).publishOn(Schedulers.boundedElastic()).map(uid -> {
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

    private void validatePrompt(String prompt) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 20000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid prompt");
        }
    }

    private Mono<Long> resolveUserId(Long paramUserId) {
        return SecurityUtils.requireCurrentUserId(paramUserId);
    }

    /**
     * 算力点数不足欠费异常全局捕获处理器 (HTTP 402 Payment Required)
     */
    @ExceptionHandler(WalletInsufficientException.class)
    @ResponseStatus(HttpStatus.PAYMENT_REQUIRED)
    public ApiResult<Map<String, Object>> handleWalletInsufficient(WalletInsufficientException e) {
        log.warn("[WALLET-INSUFFICIENT] 捕获算力点数欠费异常: {}", e.getMessage());
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
                "pipelineCapabilities", new String[]{"SCREENING", "BATCH_ANALYSIS", "COMPARISON", "SYNTHESIS", "COMPOSITE_DAG"},
                "orm", "Lombok + MyBatis-Plus 3.5.7 + PGVector",
                "timestamp", System.currentTimeMillis()
        ));
    }
}
