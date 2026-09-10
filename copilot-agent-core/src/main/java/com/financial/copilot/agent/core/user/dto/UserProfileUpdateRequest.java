package com.financial.copilot.agent.core.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * <h1>个人基本资料更新请求对象 (User Profile Update Request)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileUpdateRequest implements Serializable {

    /** 昵称 */
    private String nickname;

    /** 头像 URL */
    private String avatarUrl;

    /** 所属机构/公司 */
    private String company;

    /** 职业头衔 */
    private String occupation;

    /** 投资签名/个人简介 */
    private String bio;

    /** 常驻城市 */
    private String city;
}
