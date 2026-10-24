package com.financial.copilot.agent.tools.cache;

import java.util.Optional;

/**
 * <h1>工具执行缓存服务接口 (Tool Execution Cache)</h1>
 * <p>
 * 定义工具执行结果的读取、写入、主动失效及监控统计契约。
 * 独立于用户对话短期记忆和图谱长期记忆，专注于无状态量化事实与工具幂等调用的记忆缓存。
 * </p>
 *
 * @author FinancialCopilot
 */
public interface ToolExecutionCache {

    /**
     * 获取缓存的工具执行响应文本
     *
     * @param toolName 工具标识
     * @param cacheKey 规范化缓存键
     * @return 存在且有效则返回包装内容，否则 Optional.empty()
     */
    Optional<String> get(String toolName, String cacheKey);

    /**
     * 写入工具执行结果至缓存
     *
     * @param toolName   工具标识
     * @param cacheKey   规范化缓存键
     * @param resultJson 序列化结果 JSON 字符串
     * @param ttlSeconds 有效期 (秒)
     */
    void put(String toolName, String cacheKey, String resultJson, long ttlSeconds);

    /**
     * 主动淘汰指定工具执行记录
     *
     * @param toolName 工具标识
     * @param cacheKey 规范化缓存键
     */
    void evict(String toolName, String cacheKey);

    /**
     * 清空指定工具标识下的全部缓存条目
     *
     * @param toolName 工具标识
     */
    void clear(String toolName);

    /**
     * 清空所有工具缓存
     */
    void clearAll();

    /**
     * 获取缓存运行监控统计快照
     *
     * @return 命中率与容量快照
     */
    ToolCacheStats getStats();
}
