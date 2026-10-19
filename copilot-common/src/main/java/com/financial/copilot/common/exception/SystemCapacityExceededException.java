package com.financial.copilot.common.exception;

import lombok.Getter;

/**
 * <h1>系统全局排队容量超限异常</h1>
 * <p>
 * 触发 HTTP 503 Service Unavailable 状态码。
 * </p>
 */
@Getter
public class SystemCapacityExceededException extends RuntimeException {

    public SystemCapacityExceededException(String message) {
        super(message);
    }
}
