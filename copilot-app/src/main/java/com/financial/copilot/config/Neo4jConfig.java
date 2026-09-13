package com.financial.copilot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 按开关装配 Neo4j 驱动；关闭时下游数据与健康检查自动配置也不再创建。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "copilot.neo4j", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import(Neo4jAutoConfiguration.class)
public class Neo4jConfig {
}
