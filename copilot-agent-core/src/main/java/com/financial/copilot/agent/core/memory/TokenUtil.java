package com.financial.copilot.agent.core.memory;

import java.util.List;

/**
 * Simple utility class for estimating token counts of messages.
 * This is a naive implementation that approximates tokens by dividing
 * the total character length of all strings by four, which roughly
 * matches typical tokenization behaviour of LLM models.
 *
 * In a production system you would replace this with a proper tokenizer
 * (e.g., tiktoken) but for compilation and basic functionality this
 * lightweight version is sufficient.
 */
public class TokenUtil {
    /**
     * Estimate the number of tokens for a list of messages.
     *
     * @param messages list of text messages
     * @return estimated token count
     */
    public static int estimateTokens(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int totalChars = 0;
        for (String msg : messages) {
            if (msg != null) {
                totalChars += msg.length();
            }
        }
        // Rough approximation: 1 token ≈ 4 characters.
        return totalChars / 4;
    }

    /**
     * Estimate tokens for a single string.
     */
    public static int estimateTokens(String text) {
        if (text == null) {
            return 0;
        }
        return text.length() / 4;
    }
}
