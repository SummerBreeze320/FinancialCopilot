package com.financial.copilot.domain.user.entity;

import com.financial.copilot.domain.user.enums.KycStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <h1>金融合规实名认证档案实体 (User Identity KYC Entity)</h1>
 * <p>
 * 严格遵从金融合规反洗钱与适格投资者身份认证要求：
 * <ul>
 *   <li>敏感证件号码仅以密文或掩码形式流转，并存储 SHA-256 哈希值用于唯一性校验；</li>
 *   <li>包含严谨的状态机流转机制 (UNVERIFIED -> PENDING -> VERIFIED / REJECTED)。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserIdentity implements Serializable {

    /** 关联的用户唯一 ID */
    private Long userId;

    /** 真实法定姓名 (如 "张三") */
    private String realName;

    /** 证件类型 (如 "ID_CARD" 居民身份证, "PASSPORT" 护照) */
    @Builder.Default
    private String idCardType = "ID_CARD";

    /** 身份证号码单向哈希 (SHA-256，用于全库唯一性排重且防明文泄露) */
    private String idCardHash;

    /** 身份证号码加密密文 (用于业务追溯解密) */
    private String idCardEncrypted;

    /** 前端脱敏显示掩码 (如 "110101********1234") */
    private String idCardMasked;

    /** 实名认证审核状态 */
    @Builder.Default
    private KycStatus verifyStatus = KycStatus.PENDING;

    /** 审核驳回原因说明 */
    private String rejectReason;

    /** 认证审核通过时间戳 */
    private LocalDateTime verifiedAt;

    /** 申请提交时间戳 */
    private LocalDateTime createdAt;

    /**
     * 判断当前是否已通过实名认证
     *
     * @return true 若已通过
     */
    public boolean isVerified() {
        return verifyStatus == KycStatus.VERIFIED;
    }
}
