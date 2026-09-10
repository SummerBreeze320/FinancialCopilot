package com.financial.copilot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 金融多资产研究 Agent 主应用启动类 (Lombok + MyBatis-Plus 版)
 */
@SpringBootApplication(scanBasePackages = "com.financial.copilot")
@MapperScan("com.financial.copilot.data.fund.mapper")
public class FinancialCopilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinancialCopilotApplication.class, args);
    }
}
