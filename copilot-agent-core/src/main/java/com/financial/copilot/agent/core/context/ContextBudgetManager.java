package com.financial.copilot.agent.core.context;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>上下文 Token 预算规划管理器 (Context Budget Manager)</h1>
 * <p>
 * 遵循 Context Engineering 原则，为 System Prompt、Memory 历史、Tool Observations
 * 与生成输出规划标准预算配额，避免单次 Prompt 膨胀导致窗口溢出或推理费率飙升。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class ContextBudgetManager {

    /**
     * 单次投研调用的默认全局输入上下文 Token 上限 (如 12,000 Token)
     */
    public static final int DEFAULT_MAX_INPUT_TOKENS = 12000;

    /**
     * 历史记忆与对话最大配额 (30%)
     */
    public static final int MEMORY_TOKEN_BUDGET = 3600;

    /**
     * 工具 Observation 事实最大配额 (50%)
     */
    public static final int OBSERVATION_TOKEN_BUDGET = 6000;

    /**
     * 估算文本大致 Token 消耗 (中文约 1.5 字符/token，英文约 4 字符/token)
     *
     * @param text 输入文本
     * @return 估算 Token 数
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int nonAscii = 0;
        int ascii = 0;
        for (char c : text.toCharArray()) {
            if (c > 127) nonAscii++;
            else ascii++;
        }
        return (int) Math.ceil(nonAscii * 0.75 + ascii * 0.25);
    }

    /**
     * 校验当前上下文是否在安全预算范围内
     */
    public boolean isWithinBudget(String prompt, int maxAllowed) {
        int estimated = estimateTokens(prompt);
        return estimated <= maxAllowed;
    }

    /**
     * 优雅截断超限文本至预算范围内
     */
    public String truncateToBudget(String text, int maxTokens) {
        if (text == null || text.isEmpty()) return text;
        int estimated = estimateTokens(text);
        if (estimated <= maxTokens) {
            return text;
        }

        log.warn("[BUDGET-MANAGER] 文本超出预算 ({} > {} tokens)，触发自适应截断", estimated, maxTokens);
        // 大致按比例裁剪字符长度
        double ratio = (double) maxTokens / estimated;
        int targetLength = Math.max(50, (int) (text.length() * ratio) - 30);
        if (targetLength >= text.length()) {
            return text;
        }
        return text.substring(0, targetLength) + "\n...[超限内容已按 Token 预算策略自动截断]...";
    }
}
