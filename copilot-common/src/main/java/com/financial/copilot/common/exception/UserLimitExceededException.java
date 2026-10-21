package com.financial.copilot.common.exception;

import lombok.Getter;

/**
 * <h1>用户并发或排队容量超限异常</h1>
 * 触发 HTTP 429 Too Many Requests 状态码。
 */
@Getter
public class UserLimitExceededException extends RuntimeException {

    private final Long userId;

    public UserLimitExceededException(String message) {
        super(message);
        this.userId = null;
    }

    public UserLimitExceededException(Long userId, String message) {
        super(message);
        this.userId = userId;
    }
}
