package com.financial.copilot.data.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** Selects the relational transaction manager explicitly in the mixed JDBC/Neo4j application. */
@Configuration
public class JdbcTransactionConfig {

    @Bean("jdbcTransactionManager")
    public PlatformTransactionManager jdbcTransactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
}
