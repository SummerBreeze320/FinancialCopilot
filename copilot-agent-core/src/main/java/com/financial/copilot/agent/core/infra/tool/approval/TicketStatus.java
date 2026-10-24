package com.financial.copilot.agent.core.infra.tool.approval;

/**
 * <h1>工具审批工单状态枚举 (TicketStatus)</h1>
 *
 * @author FinancialCopilot
 */
public enum TicketStatus {
    /** 待审批 */
    PENDING,
    /** 已放行 */
    APPROVED,
    /** 已驳回 */
    REJECTED,
    /** 已超时失效 */
    EXPIRED
}
