package com.financial.copilot.controller.tool;

import com.financial.copilot.agent.core.infra.tool.approval.ToolApprovalManager;
import com.financial.copilot.agent.core.infra.tool.approval.ToolApprovalTicket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * <h1>高危工具人机协同审批控制器 (ToolApprovalController)</h1>
 * <p>提供异步工单查询与人工放行/驳回接口，支撑 Human-in-the-loop 闭环操作。</p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/research/tasks")
@RequiredArgsConstructor
public class ToolApprovalController {

    private final ToolApprovalManager approvalManager;

    /**
     * 工具审批请求体
     */
    public record ToolApprovalRequest(String ticketId, boolean approved, String reason) {}

    /**
     * 提交人工审批决策
     */
    @PostMapping("/{taskId}/approval")
    public Mono<ResponseEntity<Map<String, Object>>> submitApproval(
            @PathVariable("taskId") String taskId,
            @RequestBody ToolApprovalRequest request) {
        log.info("Received approval decision for task {}, ticket {}: approved={}, reason={}",
                taskId, request.ticketId(), request.approved(), request.reason());

        boolean success = approvalManager.resolveTicket(request.ticketId(), request.approved(), request.reason());
        if (!success) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "message", "工单不存在或已处于非待审批状态"
            )));
        }

        return Mono.just(ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "ticketId", request.ticketId(),
                "approved", request.approved()
        )));
    }

    /**
     * 获取工单详情及状态
     */
    @GetMapping("/{taskId}/approval/{ticketId}")
    public Mono<ResponseEntity<ToolApprovalTicket>> getTicketStatus(
            @PathVariable("taskId") String taskId,
            @PathVariable("ticketId") String ticketId) {
        return Mono.justOrEmpty(approvalManager.getTicket(ticketId))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
