package com.financial.copilot.data.user.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financial.copilot.domain.user.entity.UserProfile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * <h1>用户基础个人资料持久化对象 (User Profile PO)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user_profile")
public class UserProfilePO {

    @TableId(value = "user_id")
    private Long userId;

    private String nickname;

    private String avatarUrl;

    private String company;

    private String occupation;

    private String bio;

    private String city;

    private LocalDateTime updatedAt;

    public UserProfile toDomain() {
        return UserProfile.builder()
                .userId(userId)
                .nickname(nickname)
                .avatarUrl(avatarUrl)
                .company(company)
                .occupation(occupation)
                .bio(bio)
                .city(city)
                .updatedAt(updatedAt)
                .build();
    }

    public static UserProfilePO fromDomain(UserProfile domain) {
        if (domain == null) return null;
        return UserProfilePO.builder()
                .userId(domain.getUserId())
                .nickname(domain.getNickname())
                .avatarUrl(domain.getAvatarUrl())
                .company(domain.getCompany())
                .occupation(domain.getOccupation())
                .bio(domain.getBio())
                .city(domain.getCity())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
