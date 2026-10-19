package com.financial.copilot.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * <h1>金融投研 MCP 独立微服务启动入口</h1>
 * <p>
 * 默认运行在端口 8085，提供符合 Model Context Protocol 规范的非阻塞 WebFlux SSE/HTTP 工具服务。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@SpringBootApplication(scanBasePackages = {"com.financial.copilot.mcp"})
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
        log.info("""
                ================================================================================
                * FinancialCopilot MCP Tool Server Started Successfully!                       *
                * SSE Endpoint:       http://127.0.0.1:8085/sse (or /api/mcp/sse)              *
                * Messages Endpoint:  http://127.0.0.1:8085/messages (or /api/mcp/messages)    *
                * Protocol:           Anthropic MCP 2024-11-05 (JSON-RPC 2.0 over SSE/HTTP)     *
                ================================================================================
                """);
    }
}
