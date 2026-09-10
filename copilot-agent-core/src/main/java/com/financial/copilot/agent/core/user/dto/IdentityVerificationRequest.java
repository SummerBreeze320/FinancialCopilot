package com.financial.copilot.agent.core.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * <h1>金融实名认证申请请求传输对象 (KYC Request)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdentityVerificationRequest implements Serializable {

    /** 真实法定姓名 */
    private String realName;

    /** 证件类型 (ID_CARD 居民身份证, PASSPORT 护照) */
    @Builder.Default
    private String idCardType = "ID_CARD";

    /** 18位居民身份证号码或护照号 (明文传输，服务端立即进行单向哈希与加密脱敏) */
    private String idCardNo;
}
