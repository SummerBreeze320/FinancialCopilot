package com.financial.copilot.agent.core.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * <h1>管理员审核实名认证请求传输对象 (KYC Review Request)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdentityReviewRequest implements Serializable {

    /** 待审核的用户 ID */
    private Long userId;

    /** 是否审核通过 (true: 通过, false: 驳回) */
    private Boolean approved;

    /** 驳回原因说明 (若驳回则必填) */
    private String rejectReason;
}
