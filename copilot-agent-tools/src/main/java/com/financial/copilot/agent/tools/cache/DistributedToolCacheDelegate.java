package com.financial.copilot.agent.tools.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * <h1>分布式二级工具缓存委托扩展接口 (Distributed Tool Cache Delegate)</h1>
 * <p>
 * 遵循分层解耦原则，copilot-agent-tools 工具层不直接强依赖 Redis 驱动。
 * 运行时若上层模块（如 copilot-agent-core）提供了此委托 Bean（例如基于 Redis 实现），
 * CaffeineToolExecutionCache 将自动启用二级分布式缓存与多实例共享。
 * </p>
 *
 * @author FinancialCopilot
 */
public interface DistributedToolCacheDelegate {

    /**
     * 从分布式缓存读取数据
     *
     * @param key 缓存键
     * @return 存在且有效则返回包装内容，否则 Optional.empty()
     */
    Optional<String> get(String key);

    /**
     * 写入分布式缓存
     *
     * @param key   缓存键
     * @param value 缓存值
     * @param ttl   生存时间
     */
    void put(String key, String value, Duration ttl);

    /**
     * 主动淘汰分布式缓存键
     *
     * @param key 缓存键
     */
    void delete(String key);

    /**
     * 按前缀批量淘汰分布式缓存
     *
     * @param keyPattern 匹配模式 (如 "tool:cache:fund_metrics:*")
     */
    void deletePattern(String keyPattern);
}
