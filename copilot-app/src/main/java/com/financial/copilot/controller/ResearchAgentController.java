package com.financial.copilot.controller;

import com.financial.copilot.agent.core.workflow.FinancialResearchWorkflow;
import com.financial.copilot.common.event.ResearchStreamEvent;
import com.financial.copilot.common.result.ApiResult;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

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
    private final com.financial.copilot.agent.core.billing.WalletBillingService billingService;

    /**
     * 构造函数，自动注入工作流组件与计费中心
     *
     * @param workflow       投研总调度工作流
     * @param billingService 算力计费与钱包服务
     */
    public ResearchAgentController(FinancialResearchWorkflow workflow,
                                   com.financial.copilot.agent.core.billing.WalletBillingService billingService) {
        this.workflow = workflow;
        this.billingService = billingService;
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
         * 客户端选择的投研深度/思考强度档位 (LOW: 快速初筛, MIDDLE: 标准投研, HIGH: 深度推演)
         */
        private String researchDepth;

        /**
         * 客户用户系统唯一 ID (可选，默认为 1)
         */
        private Long userId;
    }

    /**
     * 阶段式复合流水线 SSE 流式交互接口
     * <p>
     * 依次产生 PLAN、STEP_START、STEP_COMPLETE、CONTENT、DONE 等结构化事件。
     * 客户端可按需传递投研深度档位 researchDepth（默认为 MIDDLE）。
     * 执行前执行算力点数前置探测（最低起步 100 点），不足则抛出 402 欠费异常。
     * </p>
     *
     * @param prompt        用户自然语言诉求
     * @param researchDepth 投研深度档位 (LOW / MIDDLE / HIGH)
     * @param userId        用户 ID (可选)
     * @return 响应式事件流
     */
    @GetMapping(value = "/chat/pipeline/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResearchStreamEvent> streamPipelineChat(
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "researchDepth", required = false) String researchDepth,
            @RequestParam(value = "userId", required = false) Long userId) {
        Long uid = userId != null ? userId : 1L;
        // 1. 前置配额与余额探测 (门槛 100 算力点)
        billingService.checkBalance(uid, 100L);

        com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel depth =
                com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel.fromString(researchDepth);
        log.info("[HTTP-SSE-PIPELINE] 收到阶段式投研流水线请求: user={}, prompt={}, depth={}", uid, prompt, depth);

        long startTime = System.currentTimeMillis();
        return workflow.executePipelineStream(prompt, depth)
                .doOnComplete(() -> {
                    long latency = System.currentTimeMillis() - startTime;
                    billingService.deductTokenPoints(uid, null, "PIPELINE_STREAM", "DEEPSEEK", "deepseek-chat", 800, 1500, latency);
                });
    }

    /**
     * 同步全量投研研报生成接口
     *
     * @param request 请求体封装（含投研问题、可选投研深度与用户 ID）
     * @return 最终研报 Markdown 结果
     */
    @PostMapping("/chat")
    public ApiResult<String> syncChat(@RequestBody ChatRequest request) {
        Long uid = request.getUserId() != null ? request.getUserId() : 1L;
        // 1. 前置配额与余额探测 (门槛 100 算力点)
        billingService.checkBalance(uid, 100L);

        com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel depth =
                com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel.fromString(request.getResearchDepth());
        log.info("[HTTP-POST] 收到同步投研分析请求: user={}, prompt={}, depth={}", uid, request.getPrompt(), depth);

        long startTime = System.currentTimeMillis();
        String report = workflow.execute(request.getPrompt(), depth);
        long latency = System.currentTimeMillis() - startTime;

        // 2. 扣费记账
        int promptTokens = Math.max(10, request.getPrompt().length() * 2);
        int completionTokens = Math.max(50, report != null ? report.length() * 2 : 100);
        billingService.deductTokenPoints(uid, null, "COMPOSITE_PIPELINE", "DEEPSEEK", "deepseek-chat",
                promptTokens, completionTokens, latency);

        return ApiResult.success(report);
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
