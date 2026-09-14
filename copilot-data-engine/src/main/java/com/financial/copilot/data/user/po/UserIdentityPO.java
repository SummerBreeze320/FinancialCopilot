package com.financial.copilot.data.user.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financial.copilot.domain.user.entity.UserIdentity;
import com.financial.copilot.domain.user.enums.KycStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * <h1>金融合规实名认证持久化对象 (User Identity KYC PO)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user_identity")
public class UserIdentityPO {

    /**
     * 所属系统用户 ID（主键）
     */
    @TableId(value = "user_id")
    private Long userId;

    /**
     * 认证真实姓名（例如 "张三"）
     */
    private String realName;

    /**
     * 证件类型（ID_CARD 居民身份证 / PASSPORT 护照等）
     */
    private String idCardType;

    /**
     * 证件号 SHA-256 不可逆哈希（用于唯一样本防重校验）
     */
    private String idCardHash;

    /**
     * AES-256 对称加密存储的完整证件密文
     */
    private String idCardEncrypted;

    /**
     * 脱敏掩码展示证件号（例如 "110101********2345"）
     */
    private String idCardMasked;

    /**
     * 实名认证审核状态：PENDING(待审核), APPROVED(审核通过), REJECTED(驳回)
     */
    private String verifyStatus;

    /**
     * 审核驳回原因说明
     */
    private String rejectReason;

    /**
     * 实名认证审核完成时间戳
     */
    private LocalDateTime verifiedAt;

    /**
     * 认证申请提交时间戳
     */
    private LocalDateTime createdAt;

    public UserIdentity toDomain() {
        return UserIdentity.builder()
                .userId(userId)
                .realName(realName)
                .idCardType(idCardType != null ? idCardType : "ID_CARD")
                .idCardHash(idCardHash)
                .idCardEncrypted(idCardEncrypted)
                .idCardMasked(idCardMasked)
                .verifyStatus(verifyStatus != null ? KycStatus.valueOf(verifyStatus) : KycStatus.PENDING)
                .rejectReason(rejectReason)
                .verifiedAt(verifiedAt)
                .createdAt(createdAt)
                .build();
    }

    public static UserIdentityPO fromDomain(UserIdentity domain) {
        if (domain == null) return null;
        return UserIdentityPO.builder()
                .userId(domain.getUserId())
                .realName(domain.getRealName())
                .idCardType(domain.getIdCardType())
                .idCardHash(domain.getIdCardHash())
                .idCardEncrypted(domain.getIdCardEncrypted())
                .idCardMasked(domain.getIdCardMasked())
                .verifyStatus(domain.getVerifyStatus() != null ? domain.getVerifyStatus().name() : KycStatus.PENDING.name())
                .rejectReason(domain.getRejectReason())
                .verifiedAt(domain.getVerifiedAt())
                .createdAt(domain.getCreatedAt())
                .build();
    }
}
