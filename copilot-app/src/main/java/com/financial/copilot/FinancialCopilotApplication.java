package com.financial.copilot;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/**
 * <h1>金融智能投研 Copilot 启动主程序 (Lite MySQL 版)</h1>
 * <p>
 * 技术栈整合：Spring Boot 3.3.4 + MyBatis-Plus 3.5.7 + MySQL 8 + 配置化 Tool 体系。
 * </p>
 *
 * @author FinancialCopilot
 */
@SpringBootApplication(scanBasePackages = "com.financial.copilot")
@MapperScan(
        basePackages = {
                "com.financial.copilot.data.fund.mapper",
                "com.financial.copilot.data.stock.mapper",
                "com.financial.copilot.data.billing.mapper",
                "com.financial.copilot.data.user.mapper",
                "com.financial.copilot.data.conversation.mapper",
                "com.financial.copilot.agent.core.memory"
        },
        annotationClass = Mapper.class
)
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
