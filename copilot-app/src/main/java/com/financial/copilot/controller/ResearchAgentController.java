package com.financial.copilot.controller;

import com.financial.copilot.agent.core.billing.WalletBillingService;
import com.financial.copilot.agent.core.user.service.UserService;
import com.financial.copilot.agent.core.workflow.FinancialResearchWorkflow;
import com.financial.copilot.common.event.ResearchStreamEvent;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.config.security.SecurityUtils;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * <h1>金融多资产智能投研 Agent REST / SSE 控制器</h1>
 * <p>
 * 提供多端交互接入端点：
 * 1. 阶段式复合流水线 SSE 流式输出接口（包含执行计划、步骤通知、Markdown 研报增量与完结信号）；
 * 2. 同步阻塞式研报生成接口（适合批处理或一次性拉取）；
 * 3. 平台健康度与资产能力矩阵探针。
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
     * 构造函数，自动注入工作流组件、计费中心与用户画像服务
     *
     * @param workflow       投研总调度工作流
     * @param billingService 算力计费与钱包服务
     * @param userService    用户业务服务
     */
    public ResearchAgentController(FinancialResearchWorkflow workflow,
                                   WalletBillingService billingService,
                                   UserService userService) {
        this.workflow = workflow;
        this.billingService = billingService;
        this.userService = userService;
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
         * 客户端是否开启深度思考推理模式（false: 极速标准投研模式 deepseek-chat, true: 深度推理思考模式 deepseek-reasoner）
         */
        private Boolean enableThinking = false;

        /**
         * 客户用户系统唯一 ID (可选，默认为 1)
         */
        private Long userId;
    }

    /**
     * 阶段式复合流水线 SSE 流式交互接口
     * <p>
     * 依次产生 PLAN、STEP_START、STEP_COMPLETE、CONTENT、DONE 等结构化事件。
     * 客户端可通过 enableThinking 一键开启深度思考推理（默认为 false 极速标准模式）。
     * 执行前执行算力点数前置探测（最低起步 100 点），不足则抛出 402 欠费异常。
     * 自动挂载当前登录用户的投资风险画像约束。
     * </p>
     *
     * @param prompt         用户自然语言诉求
     * @param enableThinking 是否开启深度思考推理模式
     * @param userId         用户 ID (可选，若不传则优先取当前已认证的 UID)
     * @return 响应式事件流
     */
    @GetMapping(value = "/chat/pipeline/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResearchStreamEvent> streamPipelineChat(
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "enableThinking", defaultValue = "false") Boolean enableThinking,
            @RequestParam(value = "userId", required = false) Long userId) {
        return resolveUserId(userId).flatMapMany(uid -> {
            boolean thinking = Boolean.TRUE.equals(enableThinking);
            // 1. 前置配额与余额探测 (门槛 100 算力点)
            billingService.checkBalance(uid, 100L);

            // 获取用户投资画像
            UserInvestmentProfile userProfile = userService.getInvestmentProfile(uid);

            log.info("[HTTP-SSE-PIPELINE] 收到阶段式投研流水线请求: user={}, prompt={}, enableThinking={}, riskLevel={}",
                    uid, prompt, thinking, userProfile != null ? userProfile.getRiskToleranceLevel() : "none");

            long startTime = System.currentTimeMillis();
            String model = thinking ? "deepseek-reasoner" : "deepseek-chat";
            return workflow.executePipelineStream(prompt, thinking, userProfile)
                    .doOnComplete(() -> {
                        long latency = System.currentTimeMillis() - startTime;
                        billingService.deductTokenPoints(uid, null, "PIPELINE_STREAM", "DEEPSEEK", model, 800, 1500, latency);
                    });
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
        return resolveUserId(request.getUserId()).map(uid -> {
            boolean thinking = Boolean.TRUE.equals(request.getEnableThinking());
            // 1. 前置配额与余额探测 (门槛 100 算力点)
            billingService.checkBalance(uid, 100L);

            // 获取用户投资画像
            UserInvestmentProfile userProfile = userService.getInvestmentProfile(uid);

            log.info("[HTTP-POST] 收到同步投研分析请求: user={}, prompt={}, enableThinking={}, riskLevel={}",
                    uid, request.getPrompt(), thinking, userProfile != null ? userProfile.getRiskToleranceLevel() : "none");

            long startTime = System.currentTimeMillis();
            String report = workflow.execute(request.getPrompt(), thinking, userProfile);
            long latency = System.currentTimeMillis() - startTime;

            // 2. 扣费记账
            String model = thinking ? "deepseek-reasoner" : "deepseek-chat";
            int promptTokens = Math.max(10, request.getPrompt().length() * 2);
            int completionTokens = Math.max(50, report != null ? report.length() * 2 : 100);
            billingService.deductTokenPoints(uid, null, "COMPOSITE_PIPELINE", "DEEPSEEK", model,
                    promptTokens, completionTokens, latency);

            return ApiResult.success(report);
        });
    }

    /**
     * 辅助解析当前有效用户 ID
     */
    private Mono<Long> resolveUserId(Long paramUserId) {
        if (paramUserId != null) {
            return Mono.just(paramUserId);
        }
        return SecurityUtils.getCurrentUserId().defaultIfEmpty(1L);
    }

    /**
     * 算力点数不足欠费异常全局捕获处理器 (HTTP 402 Payment Required)
     */
    @ExceptionHandler(com.financial.copilot.common.exception.WalletInsufficientException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.PAYMENT_REQUIRED)
    public ApiResult<Map<String, Object>> handleWalletInsufficient(com.financial.copilot.common.exception.WalletInsufficientException e) {
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
