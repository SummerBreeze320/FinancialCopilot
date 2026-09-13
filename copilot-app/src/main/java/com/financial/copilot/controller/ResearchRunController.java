package com.financial.copilot.controller;

import com.financial.copilot.agent.core.billing.WalletBillingService;
import com.financial.copilot.agent.core.conversation.ConversationPersistenceDisabledException;
import com.financial.copilot.agent.core.conversation.ConversationService;
import com.financial.copilot.agent.core.workflow.FinancialResearchWorkflow;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.config.security.SecurityUtils;
import com.financial.copilot.domain.conversation.entity.AgentToolAudit;
import com.financial.copilot.domain.conversation.entity.ConversationRun;
import com.financial.copilot.domain.conversation.model.CursorPage;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/research/runs")
public class ResearchRunController {
    private final FinancialResearchWorkflow workflow;
    private final WalletBillingService billing;
    private final ConversationService conversationService;

    public ResearchRunController(FinancialResearchWorkflow workflow,
                                 WalletBillingService billing,
                                 ConversationService conversationService) {
        this.workflow = workflow;
        this.billing = billing;
        this.conversationService = conversationService;
    }

    @GetMapping("/{runId}")
    public Mono<ApiResult<Map<String, Object>>> status(@PathVariable String runId) {
        return SecurityUtils.requireCurrentUserId(null).map(uid -> {
            var checkpoint = workflow.findCheckpoint(uid, runId).orElseThrow(this::notFound);
            return ApiResult.success(Map.of("runId", runId, "revision", checkpoint.graph().revision(),
                    "statuses", checkpoint.nodeStatuses(), "savedAt", checkpoint.savedAt()));
        });
    }

    /**
     * 分页查询指定运行的工具调用审计记录。
     *
     * @param runId  运行 ID
     * @param cursor 分页游标
     * @param limit  每页条数，范围 1..100
     */
    @GetMapping("/{runId}/tool-audits")
    public Mono<ApiResult<CursorPage<AgentToolAudit>>> toolAudits(
            @PathVariable String runId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return SecurityUtils.requireCurrentUserId(null).map(uid -> {
            int safeLimit = clampLimit(limit);
            UUID runUuid = parseRunId(runId);
            try {
                CursorPage<AgentToolAudit> page = conversationService.listToolAudits(
                        uid, runUuid, cursor, safeLimit);
                return ApiResult.success(page);
            } catch (ConversationPersistenceDisabledException e) {
                throw persistenceDisabled();
            } catch (RuntimeException e) {
                // ConversationPort 对越权/不存在的 run 通常返回空页，这里兜底
                throw notFound();
            }
        });
    }

    @PostMapping("/{runId}/cancel")
    public Mono<ApiResult<Map<String, Object>>> cancel(@PathVariable String runId) {
        return SecurityUtils.requireCurrentUserId(null).map(uid -> {
            UUID runUuid = parseRunId(runId);
            // 持久化启用时，先验证 run 归属
            verifyRunOwnership(uid, runUuid);
            if (!workflow.cancel(uid, runId, "User requested cancellation")) {
                throw notFound();
            }
            // 持久化启用时，标记助手消息为 CANCELLED
            try {
                conversationService.cancel(uid, runUuid, "User requested cancellation");
            } catch (ConversationPersistenceDisabledException ignored) {
                // 持久化禁用时跳过
            }
            return ApiResult.success(Map.of("runId", runId, "status", "CANCELLED"));
        });
    }

    @PostMapping("/{runId}/resume")
    public Mono<ApiResult<Map<String, Object>>> resume(@PathVariable String runId) {
        return SecurityUtils.requireCurrentUserId(null).map(uid -> {
            UUID runUuid = parseRunId(runId);
            // 持久化启用时，先验证 run 归属
            verifyRunOwnership(uid, runUuid);
            var checkpoint = workflow.findCheckpoint(uid, runId).orElseThrow(this::notFound);
            workflow.resume(uid, runId, usage -> billing.deductTokenPoints(uid, checkpoint.sessionKey(), "LLM_CALL",
                    usage.getProvider().name(), usage.getModel(), usage.getPromptTokens(), usage.getCompletionTokens(), usage.getLatencyMs()));
            return ApiResult.success(Map.of("runId", runId, "status", "RESUMED"));
        });
    }

    /**
     * 验证 run 是否属于当前用户。持久化启用时通过 ConversationService 验证，
     * 持久化禁用时由 workflow.findCheckpoint 的 userId 作用域自然保证。
     */
    private void verifyRunOwnership(Long userId, UUID runId) {
        try {
            java.util.Optional<ConversationRun> run = conversationService.findRun(userId, runId);
            if (run.isEmpty()) {
                throw notFound();
            }
        } catch (ConversationPersistenceDisabledException ignored) {
            // 持久化禁用时跳过验证，由 checkpoint 层保证隔离
        }
    }

    private static UUID parseRunId(String runId) {
        try {
            return UUID.fromString(runId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid runId format");
        }
    }

    private static int clampLimit(int limit) {
        if (limit < 1) return 1;
        return Math.min(limit, 100);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");
    }

    private ResponseStatusException persistenceDisabled() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "CONVERSATION_PERSISTENCE_DISABLED");
    }
}
