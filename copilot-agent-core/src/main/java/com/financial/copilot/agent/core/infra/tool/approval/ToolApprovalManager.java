package com.financial.copilot.agent.core.infra.tool.approval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>高危工具人工审批管理器 (ToolApprovalManager)</h1>
 * <p>提供异步工单创建、挂起监听及状态流转放行机制，支撑 Human-in-the-loop 人机协同安全闭环。</p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class ToolApprovalManager {

    private final Map<String, ToolApprovalTicket> tickets = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> waitFutures = new ConcurrentHashMap<>();

    /**
     * 创建待审批高危工具工单
     */
    public ToolApprovalTicket createTicket(String taskId,
                                          String toolName,
                                          Map<String, Object> arguments,
                                          String category,
                                          Duration timeout) {
        String ticketId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(timeout != null ? timeout : Duration.ofMinutes(5));

        ToolApprovalTicket ticket = ToolApprovalTicket.builder()
                .ticketId(ticketId)
                .taskId(taskId)
                .toolName(toolName)
                .arguments(arguments != null ? Map.copyOf(arguments) : Map.of())
                .riskCategory(category != null ? category : "GENERAL")
                .status(TicketStatus.PENDING)
                .createdAt(now)
                .expiresAt(expiresAt)
                .build();

        tickets.put(ticketId, ticket);
        waitFutures.put(ticketId, new CompletableFuture<>());

        log.info("Created high-risk tool approval ticket: {} for task: {}, tool: {}", ticketId, taskId, toolName);
        return ticket;
    }

    /**
     * 获取工单的异步挂起监听 Future
     */
    public CompletableFuture<Boolean> getWaitFuture(String ticketId) {
        return waitFutures.get(ticketId);
    }

    /**
     * 人工审核放行或驳回工单
     */
    public boolean resolveTicket(String ticketId, boolean approved, String reason) {
        ToolApprovalTicket ticket = tickets.get(ticketId);
        if (ticket == null) {
            log.warn("Approval ticket not found for id: {}", ticketId);
            return false;
        }

        if (ticket.getStatus() != TicketStatus.PENDING) {
            log.warn("Ticket {} is already resolved with status: {}", ticketId, ticket.getStatus());
            return false;
        }

        ticket.setStatus(approved ? TicketStatus.APPROVED : TicketStatus.REJECTED);
        ticket.setReason(reason);

        CompletableFuture<Boolean> future = waitFutures.get(ticketId);
        if (future != null && !future.isDone()) {
            future.complete(approved);
        }

        log.info("Resolved approval ticket {}: approved={}, reason={}", ticketId, approved, reason);
        return true;
    }

    /**
     * 查询工单详情
     */
    public Optional<ToolApprovalTicket> getTicket(String ticketId) {
        return Optional.ofNullable(tickets.get(ticketId));
    }
}
