package com.financial.copilot.agent.core.infra.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ToolExecutionGate 工具执行前参数校验与类型转换测试")
class ToolExecutionGateTest {

    private final ToolExecutionGate gate = new ToolExecutionGate();

    public static class SampleTools {
        public String screenFunds(
                @ToolConstraint(name = "fundCode", regexPattern = "^\\d{6}$", required = true)
                String fundCode,

                @ToolConstraint(name = "timeWindow", allowedValues = {"1y", "3y", "5y"}, required = true)
                String timeWindow,

                @ToolConstraint(name = "topN", minValue = 1.0, maxValue = 100.0, required = true)
                double topN,

                @ToolConstraint(name = "optionalRemark", required = false)
                String optionalRemark
        ) {
            return "SUCCESS";
        }
    }

    private Method getSampleMethod() throws NoSuchMethodException {
        return SampleTools.class.getMethod("screenFunds", String.class, String.class, double.class, String.class);
    }

    @Test
    @DisplayName("合法参数成功校验并通过类型转换")
    void testValidArgumentsPass() throws NoSuchMethodException {
        Method method = getSampleMethod();
        Map<String, Object> rawArgs = new HashMap<>();
        rawArgs.put("fundCode", "000001");
        rawArgs.put("timeWindow", "3y");
        rawArgs.put("topN", "20"); // 字符串形式传入，需自动兼容转为 double

        Object[] converted = gate.validateAndConvert(method, rawArgs);
        assertNotNull(converted);
        assertEquals(4, converted.length);
        assertEquals("000001", converted[0]);
        assertEquals("3y", converted[1]);
        assertEquals(20.0, converted[2]);
        assertNull(converted[3]);
    }

    @Test
    @DisplayName("必填参数缺失时抛出 InvalidToolArgumentException")
    void testMissingRequiredThrows() throws NoSuchMethodException {
        Method method = getSampleMethod();
        Map<String, Object> rawArgs = new HashMap<>();
        rawArgs.put("timeWindow", "1y");
        rawArgs.put("topN", 10.0);

        InvalidToolArgumentException ex = assertThrows(InvalidToolArgumentException.class, () ->
                gate.validateAndConvert(method, rawArgs));
        assertEquals("fundCode", ex.getParameterName());
        assertTrue(ex.getMessage().contains("必填"));
    }

    @Test
    @DisplayName("枚举范围越界时抛出 InvalidToolArgumentException")
    void testAllowedValuesViolationThrows() throws NoSuchMethodException {
        Method method = getSampleMethod();
        Map<String, Object> rawArgs = new HashMap<>();
        rawArgs.put("fundCode", "110011");
        rawArgs.put("timeWindow", "10y"); // 不在 {"1y", "3y", "5y"}
        rawArgs.put("topN", 10.0);

        InvalidToolArgumentException ex = assertThrows(InvalidToolArgumentException.class, () ->
                gate.validateAndConvert(method, rawArgs));
        assertEquals("timeWindow", ex.getParameterName());
        assertTrue(ex.getMessage().contains("枚举范围"));
    }

    @Test
    @DisplayName("数值上下限越界时抛出 InvalidToolArgumentException")
    void testRangeViolationThrows() throws NoSuchMethodException {
        Method method = getSampleMethod();
        Map<String, Object> rawArgs = new HashMap<>();
        rawArgs.put("fundCode", "110011");
        rawArgs.put("timeWindow", "1y");
        rawArgs.put("topN", 200.0); // > 100.0

        InvalidToolArgumentException ex = assertThrows(InvalidToolArgumentException.class, () ->
                gate.validateAndConvert(method, rawArgs));
        assertEquals("topN", ex.getParameterName());
        assertTrue(ex.getMessage().contains("超出范围"));
    }

    @Test
    @DisplayName("正则模式不匹配时抛出 InvalidToolArgumentException")
    void testRegexViolationThrows() throws NoSuchMethodException {
        Method method = getSampleMethod();
        Map<String, Object> rawArgs = new HashMap<>();
        rawArgs.put("fundCode", "INVALID_CODE"); // 非 6 位数字
        rawArgs.put("timeWindow", "1y");
        rawArgs.put("topN", 10.0);

        InvalidToolArgumentException ex = assertThrows(InvalidToolArgumentException.class, () ->
                gate.validateAndConvert(method, rawArgs));
        assertEquals("fundCode", ex.getParameterName());
        assertTrue(ex.getMessage().contains("正则"));
    }
}
