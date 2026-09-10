package com.financial.copilot.agent.core.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * <h1>认证授权成功响应对象 (Authentication Response)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse implements Serializable {

    /** JWT 访问令牌 */
    private String accessToken;

    /** JWT 刷新令牌 */
    private String refreshToken;

    /** 令牌类型 (Bearer) */
    @Builder.Default
    private String tokenType = "Bearer";

    /** Access Token 有效时长 (毫秒) */
    private Long expiresIn;

    /** 用户唯一主键 ID */
    private Long userId;

    /** 登录用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 角色标识列表 */
    private List<String> roles;

    /** 权限标识列表 */
    private List<String> permissions;
}
