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

    /**
     * 所属系统用户 ID（主键）
     */
    @TableId(value = "user_id")
    private Long userId;

    /**
     * 用户个性化昵称
     */
    private String nickname;

    /**
     * 用户头像 URL 地址
     */
    private String avatarUrl;

    /**
     * 任职所属金融机构或企业单位
     */
    private String company;

    /**
     * 职业头衔/岗位名称（例如 "投资总监", "基金研究员"）
     */
    private String occupation;

    /**
     * 个人简介与研究专长描述
     */
    private String bio;

    /**
     * 所在常驻城市
     */
    private String city;

    /**
     * 个人资料最后更新时间戳
     */
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
