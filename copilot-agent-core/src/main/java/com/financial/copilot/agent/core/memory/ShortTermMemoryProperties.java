package com.financial.copilot.agent.core.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * <h1>短期记忆存储与上下文窗口治理配置属性</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Component
@ConfigurationProperties(prefix = "copilot.memory.short-term")
public class ShortTermMemoryProperties {

    /**
     * 会话短期记忆在缓存（Redis / 本地存储）中的生命周期（分钟），默认 30 分钟
     */
    private long ttlMinutes = 30L;

    /**
     * 会话窗口 Token 安全配额阈值，超出后将触发自动滚动摘要提纯压缩，默认 6000 Token
     */
    private int tokenThreshold = 6000;
}
