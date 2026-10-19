package com.financial.copilot.common.guardrail;

/**
 * <h1>动作侧护栏决策枚举 (Action Guardrail Policy)</h1>
 * <p>
 * 区分三级刚性管控动作：
 * <ul>
 *     <li>ALLOW: 纯读操作/安全查询，直接放行执行</li>
 *     <li>ASK: 调仓修改/大额积分消耗等中高危操作，触发 HITL 人工审批挂起</li>
 *     <li>DENY: 越权删除/高危越界操作，硬拦截彻底拒绝</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
public enum GuardrailAction {
    /** 纯读操作/安全查询，直接放行 */
    ALLOW,
    /** 修改/删除/调仓/扣费操作，触发 HITL 人工确认弹窗 */
    ASK,
    /** 越权/违规/高危操作，硬策略直接拒绝阻断 */
    DENY
}
