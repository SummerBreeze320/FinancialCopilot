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
     * 构造函数，自动注入工作流组件
     *
     * @param workflow 投研总调度工作流
     */
    public ResearchAgentController(FinancialResearchWorkflow workflow) {
        this.workflow = workflow;
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
    }

    /**
     * 阶段式复合流水线 SSE 流式交互接口
     * <p>
     * 依次产生 PLAN、STEP_START、STEP_COMPLETE、CONTENT、DONE 等结构化事件。
     * 客户端可按需传递投研深度档位 researchDepth（默认为 MIDDLE）。
     * </p>
     *
     * @param prompt        用户自然语言诉求
     * @param researchDepth 投研深度档位 (LOW / MIDDLE / HIGH)
     * @return 响应式事件流
     */
    @GetMapping(value = "/chat/pipeline/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResearchStreamEvent> streamPipelineChat(
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "researchDepth", required = false) String researchDepth) {
        com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel depth =
                com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel.fromString(researchDepth);
        log.info("[HTTP-SSE-PIPELINE] 收到阶段式投研流水线请求: prompt={}, depth={}", prompt, depth);
        return workflow.executePipelineStream(prompt, depth);
    }

    /**
     * 同步全量投研研报生成接口
     *
     * @param request 请求体封装（含投研问题与可选投研深度）
     * @return 最终研报 Markdown 结果
     */
    @PostMapping("/chat")
    public ApiResult<String> syncChat(@RequestBody ChatRequest request) {
        com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel depth =
                com.financial.copilot.agent.core.llm.provider.LlmPerformanceLevel.fromString(request.getResearchDepth());
        log.info("[HTTP-POST] 收到同步投研分析请求: prompt={}, depth={}", request.getPrompt(), depth);
        String report = workflow.execute(request.getPrompt(), depth);
        return ApiResult.success(report);
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
