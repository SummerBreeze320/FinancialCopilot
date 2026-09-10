package com.financial.copilot.domain.user.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <h1>用户基础个人资料实体 (User Profile Entity)</h1>
 * <p>
 * 职责：维护昵称、头像、所属机构、职位背景、个人简介等非认证级辅助信息。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile implements Serializable {

    /** 关联的用户唯一 ID */
    private Long userId;

    /** 社区或显示昵称 */
    private String nickname;

    /** 头像图片 URL */
    private String avatarUrl;

    /** 所属机构/单位 (如 "中欧基金", "某某证券研究所", "个人投资者") */
    private String company;

    /** 职业头衔/身份 (如 "资深行业研究员", "FOF投资经理") */
    private String occupation;

    /** 投资签名/个人简介 */
    private String bio;

    /** 常驻城市/地区 */
    private String city;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;
}
