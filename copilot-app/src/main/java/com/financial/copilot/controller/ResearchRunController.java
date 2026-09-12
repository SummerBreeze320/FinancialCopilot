package com.financial.copilot.controller;

import com.financial.copilot.agent.core.billing.WalletBillingService;
import com.financial.copilot.agent.core.workflow.FinancialResearchWorkflow;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.config.security.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/research/runs")
public class ResearchRunController {
    private final FinancialResearchWorkflow workflow;
    private final WalletBillingService billing;

    public ResearchRunController(FinancialResearchWorkflow workflow, WalletBillingService billing) {
        this.workflow = workflow;
        this.billing = billing;
    }

    @GetMapping("/{runId}")
    public Mono<ApiResult<Map<String, Object>>> status(@PathVariable String runId) {
        return SecurityUtils.requireCurrentUserId(null).map(uid -> {
            var checkpoint = workflow.findCheckpoint(uid, runId).orElseThrow(this::notFound);
            return ApiResult.success(Map.of("runId", runId, "revision", checkpoint.graph().revision(),
                    "statuses", checkpoint.nodeStatuses(), "savedAt", checkpoint.savedAt()));
        });
    }

    @PostMapping("/{runId}/cancel")
    public Mono<ApiResult<Map<String, Object>>> cancel(@PathVariable String runId) {
        return SecurityUtils.requireCurrentUserId(null).map(uid -> {
            if (!workflow.cancel(uid, runId, "User requested cancellation")) throw notFound();
            return ApiResult.success(Map.of("runId", runId, "status", "CANCELLED"));
        });
    }

    @PostMapping("/{runId}/resume")
    public Mono<ApiResult<Map<String, Object>>> resume(@PathVariable String runId) {
        return SecurityUtils.requireCurrentUserId(null).map(uid -> {
            var checkpoint = workflow.findCheckpoint(uid, runId).orElseThrow(this::notFound);
            workflow.resume(uid, runId, usage -> billing.deductTokenPoints(uid, checkpoint.sessionId(), "LLM_CALL",
                    usage.getProvider().name(), usage.getModel(), usage.getPromptTokens(), usage.getCompletionTokens(), usage.getLatencyMs()));
            return ApiResult.success(Map.of("runId", runId, "status", "RESUMED"));
        });
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");
    }
}
