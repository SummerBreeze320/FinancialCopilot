package com.financial.copilot.domain.platform.billing.entity;

/**
 * <h1>算力预占状态枚举 (Reservation Status)</h1>
 * <p>
 * 标识投研运行生命周期内算力预占额度的流转状态。
 * </p>
 *
 * @author FinancialCopilot
 */
public enum ReservationStatus {

    /**
     * 已成功预占冻结，运行正在进行中
     */
    ACTIVE,

    /**
     * 运行已结束并完成实际算力扣减与结算
     */
    SETTLED,

    /**
     * 运行异常、取消或超时，预占额度已全额释放解冻
     */
    RELEASED
}
