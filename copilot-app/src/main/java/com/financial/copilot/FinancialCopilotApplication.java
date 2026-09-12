package com.financial.copilot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * <h1>金融智能投研 Copilot 启动主程序</h1>
 * <p>
 * 技术栈整合：Spring Boot 3.3.4 + MyBatis-Plus 3.5.7 + PostgreSQL 16 (PGVector) + DeepSeek-V3 / R1。
 * 业务领域划分：公募基金领域（Fund）全面实施落地，预留股票（Stock）、期货（Futures）、理财（Wealth）等统一接口拓展。
 * </p>
 *
 * @author FinancialCopilot
 */
@SpringBootApplication(scanBasePackages = "com.financial.copilot")
@MapperScan({
        "com.financial.copilot.data.fund.mapper",
        "com.financial.copilot.data.stock.mapper",
        "com.financial.copilot.data.billing.mapper",
        "com.financial.copilot.data.user.mapper",
        "com.financial.copilot.agent.core.memory"
})
public class FinancialCopilotApplication {

    /**
     * 应用程序入口
     *
     * @param args 命令行启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(FinancialCopilotApplication.class, args);
    }
}
