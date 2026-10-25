package com.financial.copilot.agent.tools.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * <h1>工具执行缓存自动配置装配 (Tool Cache Auto Configuration)</h1>
 *
 * @author FinancialCopilot
 */
@Configuration
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableConfigurationProperties(ToolCacheProperties.class)
@ConditionalOnProperty(prefix = "copilot.tool-cache", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ToolCacheAutoConfiguration {
}
