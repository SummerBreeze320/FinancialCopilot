package com.financial.copilot.agent.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <h1>上下文缩减与滚动摘要治理器 (Context Reducer with Rolling Summary)</h1>
 * <p>
 * 遵循 Context Engineering 理念：当会话消息超出 Token 预算时，
 * 避免粗暴盲目丢弃早期对话，而是将移除的历史执行轮次压缩为紧凑的【历史摘要】，
 * 保留关键决策链条的同时使 Token 严格保持在安全配额之内。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class ContextReducer {

    private static final Logger log = LoggerFactory.getLogger(ContextReducer.class);

    /**
     * 计算超出阈值需要从头部移除的消息数（保持向后兼容）
     *
     * @param context        当前上下文消息列表
     * @param tokenThreshold Token 阈值
     * @return 需移除的头部消息数
     */
    public int messagesToRemove(List<String> context, int tokenThreshold) {
        if (tokenThreshold < 0) {
            throw new IllegalArgumentException("Token threshold must be non-negative");
        }
        if (context == null || context.isEmpty()) return 0;
        int removed = 0;
        while (removed < context.size()
                && TokenUtil.estimateTokens(context.subList(removed, context.size())) > tokenThreshold) {
            removed++;
        }
        return removed;
    }

    /**
     * 智能压缩缩减：将早期超预算消息提炼为滚动摘要，并与近期保留消息合并
     *
     * @param context        原始上下文
     * @param tokenThreshold 预算阈值
     * @return 压缩后的上下文列表
     */
    public List<String> compressAndReduce(List<String> context, int tokenThreshold) {
        if (context == null || context.isEmpty()) {
            return new ArrayList<>();
        }
        int removedCount = messagesToRemove(context, tokenThreshold);
        if (removedCount == 0) {
            return new ArrayList<>(context);
        }

        log.info("[CONTEXT-REDUCER] 触发滑动窗口摘要压缩: 原始条数={}, 需压缩条数={}, 阈值={}",
                context.size(), removedCount, tokenThreshold);

        List<String> evicted = context.subList(0, removedCount);
        List<String> retained = new ArrayList<>(context.subList(removedCount, context.size()));

        // 生成滚动摘要
        String summary = buildEvictedSummary(evicted);
        if (!summary.isBlank()) {
            retained.add(0, summary);
        }

        return retained;
    }

    private String buildEvictedSummary(List<String> evicted) {
        if (evicted == null || evicted.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【前期历史交互摘要】:\n");
        int count = 0;
        for (String msg : evicted) {
            count++;
            String line = msg.trim().replaceAll("\n+", " ");
            if (line.length() > 80) {
                line = line.substring(0, 80) + "...";
            }
            sb.append("- 步骤/轮次").append(count).append(": ").append(line).append("\n");
            if (count >= 5) {
                int remaining = evicted.size() - count;
                if (remaining > 0) {
                    sb.append("  (其余 ").append(remaining).append(" 条早期记录已按 Token 预算归档)\n");
                }
                break;
            }
        }
        return sb.toString().trim();
    }
}
