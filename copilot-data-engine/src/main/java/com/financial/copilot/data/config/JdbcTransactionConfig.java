package com.financial.copilot.data.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * <h1>JDBC 关系数据库事务管理器配置</h1>
 * <p>
 * 在混合使用 PostgreSQL 关系型数据库与 Neo4j 图数据库的分布式投研架构中，
 * 显式声明命名为 {@code jdbcTransactionManager} 的 {@link PlatformTransactionManager} Bean，
 * 防止声明式事务 {@code @Transactional} 发生多数据源上下文歧义。
 * </p>
 *
 * @author FinancialCopilot
 */
@Configuration
public class JdbcTransactionConfig {

    @Bean("jdbcTransactionManager")
    public PlatformTransactionManager jdbcTransactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
}
