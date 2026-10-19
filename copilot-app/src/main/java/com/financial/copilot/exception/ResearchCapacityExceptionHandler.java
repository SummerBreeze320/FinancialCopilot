package com.financial.copilot.exception;

import com.financial.copilot.common.exception.SystemCapacityExceededException;
import com.financial.copilot.common.exception.UserLimitExceededException;
import com.financial.copilot.common.result.ApiResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * <h1>图运行容量与过载异常处理器</h1>
 * <p>
 * 将单用户并发/排队上限超限映射为 HTTP 429 Too Many Requests，
 * 将系统全局容量排队超限映射为 HTTP 503 Service Unavailable。
 * </p>
 */
@RestControllerAdvice
public class ResearchCapacityExceptionHandler {

    @ExceptionHandler(UserLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ResponseEntity<ApiResult<Map<String, Object>>> handleUserLimitExceeded(UserLimitExceededException ex) {
        ApiResult<Map<String, Object>> result = ApiResult.<Map<String, Object>>builder()
                .code(429)
                .message(ex.getMessage())
                .data(Map.of("userId", ex.getUserId() != null ? ex.getUserId() : 0L, "reason", "USER_LIMIT_EXCEEDED"))
                .timestamp(System.currentTimeMillis())
                .build();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(result);
    }

    @ExceptionHandler(SystemCapacityExceededException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ResponseEntity<ApiResult<Map<String, Object>>> handleSystemCapacityExceeded(SystemCapacityExceededException ex) {
        ApiResult<Map<String, Object>> result = ApiResult.<Map<String, Object>>builder()
                .code(503)
                .message(ex.getMessage())
                .data(Map.of("reason", "SYSTEM_CAPACITY_EXHAUSTED"))
                .timestamp(System.currentTimeMillis())
                .build();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
    }
}
