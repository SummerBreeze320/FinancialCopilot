package com.financial.copilot.agent.core.infra.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * <h1>MCP 客户端配置属性</h1>
 * <p>
 * 控制 AgentCore 是否通过外部 independent 模块 {@code copilot-mcp-service} 代理执行工具。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Component
@ConfigurationProperties(prefix = "copilot.mcp")
public class McpClientProperties {

    /**
     * 是否启用 MCP 代理模式（默认为 false，若为 true 则优先通过 copilot-mcp-service 调度工具）
     */
    private boolean enabled = false;

    /**
     * MCP 服务端基础 URL 地址（默认指向独立的 copilot-mcp-service 8085 端口）
     */
    private String baseUrl = "http://127.0.0.1:8085";

    /**
     * 工具调用远程超时时间（秒，默认 15 秒）
     */
    private int timeoutSeconds = 15;
}
