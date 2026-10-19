package com.financial.copilot.mcp.config;

import com.financial.copilot.mcp.registry.DynamicToolConfig;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * <h1>MCP 服务端核心配置属性</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "mcp.server")
public class McpServerProperties {

    /**
     * 服务运行端口（默认 8085）
     */
    private int port = 8085;

    /**
     * MCP 暴露的服务名称
     */
    private String name = "financial-copilot-tools";

    /**
     * MCP 服务版本号
     */
    private String version = "1.0.0";

    /**
     * YAML 声明式配置的动态工具列表
     */
    private List<DynamicToolConfig> dynamicTools = new ArrayList<>();
}
