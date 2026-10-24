package com.financial.copilot.agent.tools.cache;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <h1>工具执行结果缓存注解 (Tool Cacheable)</h1>
 * <p>
 * 标注在金融投研工具或数据检索方法上。当相同工具以完全一致的参数被重复调用时，
 * 优先从本地/分布式工具缓存中直接返回历史结果，避免高耗时的重复量化计算、数据库检索或 MCP 网络往返。
 * </p>
 *
 * @author FinancialCopilot
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface ToolCacheable {

    /**
     * 工具标识名称。若为空则默认取方法名或类名衍生标识。
     *
     * @return 工具名称
     */
    String value() default "";

    /**
     * 缓存生存时间 (TTL)，单位为秒。默认为 300 秒 (5分钟)。
     *
     * @return 缓存有效秒数
     */
    long ttlSeconds() default 300;

    /**
     * 是否缓存包含 ERROR/EXCEPTION 状态的失败响应。默认为 false，保证下游重试能正常穿透执行。
     *
     * @return true 允许缓存错误结果，false 忽略错误结果
     */
    boolean cacheErrors() default false;
}
