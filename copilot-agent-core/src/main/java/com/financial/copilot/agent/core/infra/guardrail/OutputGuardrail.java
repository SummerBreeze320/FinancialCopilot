package com.financial.copilot.agent.core.infra.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * <h1>输出护栏策略 (OutputGuardrail)</h1>
 * <p>对智能体最终输出进行 PII 敏感隐私信息掩码脱敏 (手机号、身份证、银行卡) 并注入合规免责声明。</p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class OutputGuardrail {

    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)(\\d{6})\\d{8}(\\d{3}[0-9Xx])(?!\\d)");
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile("(?<!\\d)(\\d{6})\\d{6,9}(\\d{4})(?!\\d)");

    private static final String COMPLIANCE_DISCLAIMER =
            "\n\n【免责声明】本报告内容由 AI 智能投研助手根据公开市场数据分析生成，仅供参考，不构成任何投资建议或收益承诺。市场有风险，投资需谨慎。";

    /**
     * 审查净化最终输出文本
     *
     * @param output 待输出文本
     * @return 经过脱敏与免责声明注入后的合规文本
     */
    public String sanitize(String output) {
        if (output == null || output.isBlank()) {
            return output;
        }

        String sanitized = output;

        // 1. 手机号脱敏
        sanitized = PHONE_PATTERN.matcher(sanitized).replaceAll("$1****$2");

        // 2. 身份证脱敏
        sanitized = ID_CARD_PATTERN.matcher(sanitized).replaceAll("$1********$2");

        // 3. 银行卡脱敏
        sanitized = BANK_CARD_PATTERN.matcher(sanitized).replaceAll("$1*********$2");

        // 4. 合规免责声明注入 (若尚未包含)
        if (!sanitized.contains("【免责声明】")) {
            sanitized = sanitized + COMPLIANCE_DISCLAIMER;
        }

        return sanitized;
    }
}
