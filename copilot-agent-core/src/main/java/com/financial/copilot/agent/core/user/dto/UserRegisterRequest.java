package com.financial.copilot.agent.core.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * <h1>用户注册请求传输对象 (User Register Request)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRegisterRequest implements Serializable {

    /** 登录账号 (4-30位字母/数字/下划线) */
    private String username;

    /** 明文初始密码 (至少6位) */
    private String password;

    /** 绑定手机号 (可选) */
    private String mobile;

    /** 绑定电子邮箱 (可选) */
    private String email;

    /** 初始昵称 (可选) */
    private String nickname;
}
