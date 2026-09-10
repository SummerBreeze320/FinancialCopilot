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

    @TableId(value = "user_id")
    private Long userId;

    private String realName;

    private String idCardType;

    private String idCardHash;

    private String idCardEncrypted;

    private String idCardMasked;

    private String verifyStatus;

    private String rejectReason;

    private LocalDateTime verifiedAt;

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
