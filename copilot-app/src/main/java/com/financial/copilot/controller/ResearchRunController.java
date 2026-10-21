package com.financial.copilot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.infra.supervisor.runtime.ResearchRunHandle;
import com.financial.copilot.agent.core.infra.workflow.FinancialResearchWorkflow;
import com.financial.copilot.agent.core.platform.billing.WalletBillingService;
import com.financial.copilot.agent.core.platform.conversation.ConversationPersistenceDisabledException;
import com.financial.copilot.agent.core.platform.conversation.ConversationService;
import com.financial.copilot.common.event.ResearchStreamEvent;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.config.security.SecurityUtils;
import com.financial.copilot.domain.platform.conversation.entity.AgentToolAudit;
import com.financial.copilot.domain.platform.conversation.entity.ConversationRun;
import com.financial.copilot.domain.platform.conversation.model.CursorPage;
import com.financial.copilot.domain.platform.execution.entity.ResearchRunEvent;
import com.financial.copilot.domain.platform.execution.port.ResearchExecutionPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <h1>金融投研运行生命周期与状态控制控制器 (ResearchRunController)</h1>
 * <p>
 * 提供投研运行状态检索、工具调用审计明细分页、运行取消、运行断点恢复以及 SSE 有序事件重放。
 * </p>
 *
 * @author FinancialCopilot
 */
@RestController
@RequestMapping("/api/v1/research/runs")
public class ResearchRunController {

    private final FinancialResearchWorkflow workflow;
    private final WalletBillingService billing;
    private final ConversationService conversationService;
    private final ResearchExecutionPort executionPort;
    private final ObjectMapper json;

    public ResearchRunController(FinancialResearchWorkflow workflow,
                                 WalletBillingService billing,
                                 ConversationService conversationService) {
        this(workflow, billing, conversationService, null, null);
    }

    @Autowired
    public ResearchRunController(FinancialResearchWorkflow workflow,
                                 WalletBillingService billing,
                                 ConversationService conversationService,
                                 @Autowired(required = false) ResearchExecutionPort executionPort,
                                 @Autowired(required = false) ObjectMapper json) {
        this.workflow = workflow;
        this.billing = billing;
        this.conversationService = conversationService;
        this.executionPort = executionPort;
        this.json = json != null ? json : new ObjectMapper();
    }

    @GetMapping("/{runId}")
    public Mono<ApiResult<Map<String, Object>>> status(@PathVariable String runId) {
        return SecurityUtils.requireCurrentUserId(null).map(uid -> {
            Optional<ResearchRunHandle> activeHandle = workflow.findRun(uid, runId);
            if (activeHandle.isPresent()) {
                return ApiResult.success(Map.of("runId", runId, "status", "RUNNING"));
            }
            if (executionPort != null) {
                UUID runUuid = parseRunId(runId);
                var runStateOpt = executionPort.findRunState(uid, runUuid);
                if (runStateOpt.isPresent()) {
                    var runState = runStateOpt.get();
                    return ApiResult.success(Map.of("runId", runId, "status", runState.status(),
                            "version", runState.version(), "updatedAt", runState.updatedAt()));
                }
            }
            throw notFound();
        });
    }

    /**
     * SSE 运行事件重放与实时订阅接口 (支持 Last-Event-ID 游标续传)。
     *
     * @param runId       运行 ID
     * @param lastEventId 客户端上次接收到的最大事件序号
     * @return 响应式事件流
     */
    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResearchStreamEvent> replayEvents(
            @PathVariable String runId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        return SecurityUtils.requireCurrentUserId(null).flatMapMany(uid -> {
            UUID runUuid = parseRunId(runId);
            verifyRunOwnership(uid, runUuid);

            long afterSeq = 0L;
            if (lastEventId != null && !lastEventId.isBlank()) {
                try {
                    afterSeq = Long.parseLong(lastEventId.trim());
                } catch (NumberFormatException ignored) {}
            }

            List<ResearchStreamEvent> replayed = List.of();
            if (executionPort != null) {
                List<ResearchRunEvent> persisted = executionPort.getEventsAfter(uid, runUuid, afterSeq);
                replayed = persisted.stream().map(this::toStreamEvent).toList();
            }

            long highestSeq = replayed.isEmpty() ? afterSeq : replayed.get(replayed.size() - 1).getSequenceNo();
            Flux<ResearchStreamEvent> replayFlux = Flux.fromIterable(replayed);

            Optional<ResearchRunHandle> activeHandle = workflow.findRun(uid, runId);
            if (activeHandle.isPresent()) {
                Flux<ResearchStreamEvent> liveFlux = activeHandle.get().events()
                        .filter(e -> e.getSequenceNo() == null || e.getSequenceNo() > highestSeq);
                return replayFlux.concatWith(liveFlux);
            }

            return replayFlux;
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
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DURABLE_RESUME_UNAVAILABLE");
        });
    }

    private ResearchStreamEvent toStreamEvent(ResearchRunEvent event) {
        try {
            ResearchStreamEvent streamEvent = json.readValue(event.payload(), ResearchStreamEvent.class);
            streamEvent.setSequenceNo(event.sequenceNo());
            return streamEvent;
        } catch (Exception e) {
            return ResearchStreamEvent.builder()
                    .type(event.eventType())
                    .runId(event.runId().toString())
                    .sequenceNo(event.sequenceNo())
                    .build();
        }
    }

    /**
     * 验证 run 是否属于当前用户。持久化启用时通过 ConversationService 验证，
     * 持久化禁用时由 workflow.findCheckpoint 的 userId 作用域自然保证。
     */
    private void verifyRunOwnership(Long userId, UUID runId) {
        try {
            Optional<ConversationRun> run = conversationService.findRun(userId, runId);
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
