package com.financial.copilot.agent.core.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * <h1>用户登录请求传输对象 (User Login Request)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLoginRequest implements Serializable {

    /** 登录账号 / 手机号 / 邮箱 */
    private String username;

    /** 登录密码明文 */
    private String password;
}
