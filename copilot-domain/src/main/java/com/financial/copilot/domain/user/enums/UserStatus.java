package com.financial.copilot.domain.user.enums;

import lombok.Getter;

/**
 * <h1>系统用户账号状态枚举 (User Status)</h1>
 *
 * @author FinancialCopilot
 */
@Getter
public enum UserStatus {

    ACTIVE("正常启用"),
    LOCKED("风控锁定"),
    DISABLED("已注销/停用");

    private final String description;

    UserStatus(String description) {
        this.description = description;
    }
}
