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
 * 金融多资产投研 Agent REST / SSE 交互控制器
 * 提供单步直达与高阶长链路流水线交互端点
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/research")
public class ResearchAgentController {

    private final FinancialResearchWorkflow workflow;

    public ResearchAgentController(FinancialResearchWorkflow workflow) {
        this.workflow = workflow;
    }

    @Data
    public static class ChatRequest {
        private String prompt;
    }

    /**
     * 阶段式复合流水线 SSE 流式交互接口 (输出 PLAN, STEP_START, STEP_COMPLETE, CONTENT, DONE)
     */
    @GetMapping(value = "/chat/pipeline/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResearchStreamEvent> streamPipelineChat(@RequestParam("prompt") String prompt) {
        log.info("[HTTP-SSE-PIPELINE] 收到阶段式投研流水线请求: prompt={}", prompt);
        return workflow.executePipelineStream(prompt);
    }

    /**
     * 纯打字机 Markdown 文本流接口 (兼容简易聊天窗口)
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestParam("prompt") String prompt) {
        log.info("[HTTP-SSE] 收到流式打字机请求: prompt={}", prompt);
        return workflow.executeStream(prompt);
    }

    /**
     * 同步全量投研研报生成接口
     */
    @PostMapping("/chat")
    public ApiResult<String> syncChat(@RequestBody ChatRequest request) {
        log.info("[HTTP-POST] 收到同步投研分析请求: prompt={}", request.getPrompt());
        String report = workflow.execute(request.getPrompt());
        return ApiResult.success(report);
    }

    /**
     * 健康检查与平台能力清单
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
