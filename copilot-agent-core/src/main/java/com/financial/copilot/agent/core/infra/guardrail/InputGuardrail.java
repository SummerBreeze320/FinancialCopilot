package com.financial.copilot.agent.core.infra.guardrail;

import com.financial.copilot.common.intent.IntentClassificationResult;
import com.financial.copilot.common.intent.IntentType;
import com.financial.copilot.common.intent.MatchingLayer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * <h1>输入护栏策略 (InputGuardrail)</h1>
 * <p>对用户 Prompt 进行对抗样本检测、越狱词过滤及敏感词拦截，保障智能体输入安全。</p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class InputGuardrail {

    private static final List<String> INJECTION_PATTERNS = List.of(
            "忽略所有规则",
            "ignore previous instructions",
            "系统提示词",
            "system prompt",
            "越狱",
            "jailbreak",
            "管理员模式",
            "admin mode",
            "bypass safety",
            "绕过限制"
    );

    /**
     * 审核并净化用户意图输入
     *
     * @param query 用户原始输入
     * @param candidate 流水线初筛的意图分类结果
     * @return 审核后的意图分类结果，若命中违规注入则重置为 OOD 拒识
     */
    public IntentClassificationResult auditAndSanitize(String query, IntentClassificationResult candidate) {
        if (query == null || query.isBlank()) {
            return candidate;
        }

        String lower = query.toLowerCase();
        for (String pattern : INJECTION_PATTERNS) {
            if (lower.contains(pattern)) {
                log.warn("Input guardrail triggered for pattern '{}' in query: {}", pattern, query);
                return new IntentClassificationResult(
                        IntentType.OUT_OF_DOMAIN,
                        1.0,
                        Map.of("blocked_pattern", pattern, "guardrail_type", "INPUT_INJECTION"),
                        MatchingLayer.RULE,
                        true,
                        "请求已被输入护栏策略拦截：检测到对抗性或敏感系统指令，系统拒绝执行。"
                );
            }
        }

        return candidate;
    }
}
