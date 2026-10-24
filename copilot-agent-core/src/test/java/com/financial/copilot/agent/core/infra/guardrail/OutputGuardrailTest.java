package com.financial.copilot.agent.core.infra.guardrail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OutputGuardrail 输出护栏脱敏与合规声明测试")
class OutputGuardrailTest {

    private OutputGuardrail outputGuardrail;

    @BeforeEach
    void setUp() {
        outputGuardrail = new OutputGuardrail();
    }

    @Test
    @DisplayName("手机号、身份证号、银行卡号自动掩码脱敏")
    void testMaskSensitivePii() {
        String input = "用户手机: 13812345678, 身份证: 110101199003072345, 银行卡: 6222021234567890123";
        String sanitized = outputGuardrail.sanitize(input);

        // 验证手机号脱敏
        assertFalse(sanitized.contains("13812345678"));
        assertTrue(sanitized.contains("138****5678"));

        // 验证身份证脱敏
        assertFalse(sanitized.contains("110101199003072345"));
        assertTrue(sanitized.contains("110101********2345"));

        // 验证银行卡脱敏
        assertFalse(sanitized.contains("6222021234567890123"));
        assertTrue(sanitized.contains("622202*********0123"));
    }

    @Test
    @DisplayName("合规免责声明自动注入")
    void testInjectComplianceDisclaimer() {
        String input = "易方达蓝筹精选近三年夏普比率 0.85，建议逢低布局。";
        String sanitized = outputGuardrail.sanitize(input);

        assertTrue(sanitized.contains("【免责声明】"));
        assertTrue(sanitized.contains("不构成任何投资建议"));
    }
}
