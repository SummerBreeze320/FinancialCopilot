package com.financial.copilot.agent.core.memory;

import java.util.List;

/**
 * <h1>Token 消耗估算工具 (TokenUtil)</h1>
 * <p>
 * 针对金融中英文混合、多轮投研研报与代词追踪场景设计的高性能 Token 估算器：
 * <ul>
 *     <li>CJK 汉字与全角标点符号：基于主流大模型（DeepSeek / Qwen）BPE 分词经验加权，约合 1.3 Token/字；</li>
 *     <li>基础 ASCII（英文词根、数字代码、半角标点）：按 4 字符/Token（0.25 Token/字）换算；</li>
 *     <li>单次内存扫描，无任何外部库依赖，达到微秒级执行吞吐。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
public final class TokenUtil {

    private TokenUtil() {}

    /**
     * 估算消息列表的总 Token 消耗
     *
     * @param messages 消息文本列表
     * @return 估算的 Token 总数
     */
    public static int estimateTokens(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String msg : messages) {
            total += estimateTokens(msg);
        }
        return total;
    }

    /**
     * 估算单段文本的 Token 消耗
     *
     * @param text 待估算文本
     * @return 估算的 Token 数
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0.0;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                tokens += 1.3;
            } else {
                tokens += 0.25;
            }
        }
        return (int) Math.ceil(tokens);
    }

    /**
     * 判定是否为 CJK 汉字或全角表意标点
     */
    public static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }
}
