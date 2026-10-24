package com.financial.copilot.agent.core.infra.tool.approval;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * <h1>高危工具人工审批工单 (ToolApprovalTicket)</h1>
 * <p>封装高危工具调用上下文、入参镜像及人机协同审核状态。</p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
public class ToolApprovalTicket {
    /** 工单唯一标识 (UUID) */
    private String ticketId;

    /** 关联的任务/会话标识 */
    private String taskId;

    /** 调用的高危工具名称 */
    private String toolName;

    /** 工具入参镜像 */
    private Map<String, Object> arguments;

    /** 风险分类 (如 ASSET_TRANSACTION, ACCOUNT_MODIFICATION) */
    private String riskCategory;

    /** 当前审批状态 */
    private TicketStatus status;

    /** 审批或驳回原因说明 */
    private String reason;

    /** 创建时间 */
    private Instant createdAt;

    /** 超时截止时间 */
    private Instant expiresAt;
}
