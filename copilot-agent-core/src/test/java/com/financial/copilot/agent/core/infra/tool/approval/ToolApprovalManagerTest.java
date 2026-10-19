package com.financial.copilot.agent.core.infra.tool.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ToolApprovalManager 人机协同审批门禁管理测试")
class ToolApprovalManagerTest {

    private ToolApprovalManager manager;

    @BeforeEach
    void setUp() {
        manager = new ToolApprovalManager();
    }

    @Test
    @DisplayName("创建审批工单并等待人工放行通过")
    void testCreateAndApproveTicket() throws Exception {
        ToolApprovalTicket ticket = manager.createTicket("task-100", "rebalance_portfolio",
                Map.of("ratio", 0.5), "ASSET_TRANSACTION", Duration.ofSeconds(5));

        assertNotNull(ticket.getTicketId());
        assertEquals(TicketStatus.PENDING, ticket.getStatus());
        assertEquals("task-100", ticket.getTaskId());

        CompletableFuture<Boolean> waitFuture = manager.getWaitFuture(ticket.getTicketId());
        assertFalse(waitFuture.isDone());

        // 人工通过
        boolean resolved = manager.resolveTicket(ticket.getTicketId(), true, "人工确认调仓比例");
        assertTrue(resolved);
        assertTrue(waitFuture.get(1, TimeUnit.SECONDS));

        Optional<ToolApprovalTicket> current = manager.getTicket(ticket.getTicketId());
        assertTrue(current.isPresent());
        assertEquals(TicketStatus.APPROVED, current.get().getStatus());
        assertEquals("人工确认调仓比例", current.get().getReason());
    }

    @Test
    @DisplayName("人工驳回工单时返回 false")
    void testRejectTicket() throws Exception {
        ToolApprovalTicket ticket = manager.createTicket("task-200", "transfer_fund",
                Map.of("amount", 1000000), "FUND_TRANSFER", Duration.ofSeconds(5));

        CompletableFuture<Boolean> waitFuture = manager.getWaitFuture(ticket.getTicketId());
        manager.resolveTicket(ticket.getTicketId(), false, "金额过大，风控驳回");

        assertFalse(waitFuture.get(1, TimeUnit.SECONDS));
        assertEquals(TicketStatus.REJECTED, manager.getTicket(ticket.getTicketId()).orElseThrow().getStatus());
    }
}
