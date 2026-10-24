package com.financial.copilot.controller.tool;

import com.financial.copilot.agent.core.infra.tool.approval.TicketStatus;
import com.financial.copilot.agent.core.infra.tool.approval.ToolApprovalManager;
import com.financial.copilot.agent.core.infra.tool.approval.ToolApprovalTicket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@DisplayName("ToolApprovalController 人机协同工单审批控制器测试")
class ToolApprovalControllerTest {

    private final ToolApprovalManager approvalManager = mock(ToolApprovalManager.class);
    private final ToolApprovalController controller = new ToolApprovalController(approvalManager);

    @Test
    @DisplayName("放行通过高危工具工单成功返回 200 OK")
    void testApproveTicketSuccess() {
        when(approvalManager.resolveTicket("ticket-1", true, "人工核准")).thenReturn(true);

        ToolApprovalController.ToolApprovalRequest request =
                new ToolApprovalController.ToolApprovalRequest("ticket-1", true, "人工核准");

        ResponseEntity<Map<String, Object>> response = controller.submitApproval("task-1", request).block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("SUCCESS", response.getBody().get("status"));
        assertEquals("ticket-1", response.getBody().get("ticketId"));
        assertEquals(true, response.getBody().get("approved"));
    }

    @Test
    @DisplayName("工单不存在或已处理返回 400 Bad Request")
    void testApproveTicketNotFound() {
        when(approvalManager.resolveTicket("ticket-unknown", true, "未知")).thenReturn(false);

        ToolApprovalController.ToolApprovalRequest request =
                new ToolApprovalController.ToolApprovalRequest("ticket-unknown", true, "未知");

        ResponseEntity<Map<String, Object>> response = controller.submitApproval("task-1", request).block();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("ERROR", response.getBody().get("status"));
    }

    @Test
    @DisplayName("查询工单状态详情")
    void testGetTicketStatus() {
        ToolApprovalTicket ticket = ToolApprovalTicket.builder()
                .ticketId("ticket-2")
                .taskId("task-2")
                .toolName("rebalance_fund")
                .status(TicketStatus.PENDING)
                .riskCategory("ASSET_TRANSACTION")
                .createdAt(Instant.now())
                .build();

        when(approvalManager.getTicket("ticket-2")).thenReturn(Optional.of(ticket));

        ResponseEntity<ToolApprovalTicket> response = controller.getTicketStatus("task-2", "ticket-2").block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ticket-2", response.getBody().getTicketId());
        assertEquals(TicketStatus.PENDING, response.getBody().getStatus());
    }
}
