package com.financial.copilot.agent.core.infra.tool.reflection;

import com.financial.copilot.agent.core.infra.tool.InvalidToolArgumentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ToolReflectionHandler 工具自省反思循环与降级熔断测试")
class ToolReflectionHandlerTest {

    private ToolReflectionHandler reflectionHandler;

    @BeforeEach
    void setUp() {
        reflectionHandler = new ToolReflectionHandler();
    }

    @Test
    @DisplayName("首次工具参数异常生成结构化自省 Observation 修正提示")
    void testConstructReflectionObservation() {
        InvalidToolArgumentException ex = new InvalidToolArgumentException(
                "timeWindow", "10y", "参数 [timeWindow] 值 '10y' 不在允许的枚举范围 [1y, 3y, 5y] 内");

        ToolReflectionResult result = reflectionHandler.handleError(
                "step-1", "screen_funds", Map.of("timeWindow", "10y"), ex);

        assertTrue(result.shouldRetry());
        assertEquals(1, result.currentRetryCount());
        assertTrue(result.feedbackObservation().contains("[工具调用错误自省]"));
        assertTrue(result.feedbackObservation().contains("timeWindow"));
        assertTrue(result.feedbackObservation().contains("[1y, 3y, 5y]"));
    }

    @Test
    @DisplayName("连续重试超过最大重试次数 (2次) 触发熔断降级")
    void testMaxRetriesTripsCircuitBreaker() {
        InvalidToolArgumentException ex = new InvalidToolArgumentException(
                "fundCode", "ABC", "参数 [fundCode] 不符合 6 位数字正则");

        // 第 1 次重试
        ToolReflectionResult r1 = reflectionHandler.handleError("step-retry", "fund_metrics", Map.of("fundCode", "ABC"), ex);
        assertTrue(r1.shouldRetry());
        assertEquals(1, r1.currentRetryCount());

        // 第 2 次重试
        ToolReflectionResult r2 = reflectionHandler.handleError("step-retry", "fund_metrics", Map.of("fundCode", "ABC"), ex);
        assertTrue(r2.shouldRetry());
        assertEquals(2, r2.currentRetryCount());

        // 第 3 次超限 -> 不再重试，进入降级熔断
        ToolReflectionResult r3 = reflectionHandler.handleError("step-retry", "fund_metrics", Map.of("fundCode", "ABC"), ex);
        assertFalse(r3.shouldRetry());
        assertEquals(3, r3.currentRetryCount());
        assertTrue(r3.feedbackObservation().contains("已达最大重试上限"));
        assertNotNull(r3.fallbackResponse());
    }
}
