package com.financial.copilot.agent.tools.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <h1>工具执行缓存全局配置属性 (Tool Cache Properties)</h1>
 *
 * @author FinancialCopilot
 */
@Data
@ConfigurationProperties(prefix = "copilot.tool-cache")
public class ToolCacheProperties {

    /**
     * 是否启用工具执行结果缓存，默认为 true
     */
    private boolean enabled = true;

    /**
     * 一级内存缓存最大容量 (条目数)，默认为 5000
     */
    private long l1MaxSize = 5000;

    /**
     * 默认工具缓存存活时长 (TTL，秒)，默认为 300 秒 (5分钟)
     */
    private long defaultTtlSeconds = 300;

    /**
     * 是否将 Redis 作为二级共享缓存 (若 Redis 离线或未启用则平滑降级为纯本地缓存)
     */
    private boolean redisEnabled = true;
}
