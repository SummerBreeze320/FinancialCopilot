package com.financial.copilot.agent.tools.cache;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * <h1>工具执行缓存运行时统计快照 (Tool Cache Stats)</h1>
 * <p>
 * 记录命中次数、未命中次数、缓存写入数及命中率等观测指标。
 * </p>
 *
 * @author FinancialCopilot
 */
@Getter
@Builder
@ToString
public class ToolCacheStats {

    /**
     * 缓存命中总次数
     */
    private final long hitCount;

    /**
     * 缓存未命中总次数
     */
    private final long missCount;

    /**
     * 写入/更新缓存总次数
     */
    private final long putCount;

    /**
     * 驱逐或过期清除次数
     */
    private final long evictionCount;

    /**
     * 当前一级缓存常驻元素容量
     */
    private final long currentSize;

    /**
     * 计算当前总体缓存命中率 (0.0 ~ 1.0)
     *
     * @return 命中率比率
     */
    public double getHitRate() {
        long total = hitCount + missCount;
        if (total == 0) {
            return 0.0;
        }
        return (double) hitCount / (double) total;
    }
}
