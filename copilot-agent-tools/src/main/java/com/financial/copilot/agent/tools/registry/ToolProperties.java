package com.financial.copilot.agent.tools.registry;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Tool 模块配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "financial.copilot.tools")
public class ToolProperties {

    /** 拆分后的 tool 配置文件路径模式，递归扫描 analysis 与 comparison 子目录 */
    private String configPattern = "classpath*:config/tools/**/*.json";

    /** 远程 HTTP 调用超时时间(ms)，默认 15000 */
    private int httpTimeoutMs = 15000;

    /** 是否开启内存数据蒸馏 (方案 A) */
    private boolean enableDistillation = true;

    /** Wind 组件按需调用服务基地址 (如 WIND_FUNDRESEARCH_SERVICE_URL) */
    private String componentInvokeServiceUrl;

    /** 1. 基础数据网关配置 (FundInfraWeb) */
    private InfraWebProperties infraWeb = new InfraWebProperties();

    /** 2. 多节点 MCP 服务配置 (WindMCP) */
    private McpProperties mcp = new McpProperties();

    /** 3. 开放工具市场配置 (AIMarket) */
    private AiMarketProperties aimarket = new AiMarketProperties();

    @Data
    public static class InfraWebProperties {
        private String serviceUrl = "";
        private int timeoutSeconds = 60;
        private boolean verifyTls = false;
    }

    @Data
    public static class McpProperties {
        private String baseUrl = "https://114.80.154.45/Wind.MCP.Server/vserver";
        private int timeoutSeconds = 60;
        private boolean verifyTls = false;
        private Map<String, String> servers = new HashMap<>();

        public McpProperties() {
            servers.put("wind-fund-data", "https://114.80.154.45/Wind.MCP.Server/vserver/vserver_fund_datatest/mcp");
            servers.put("wind-fund-holdings", "https://114.80.154.45/Wind.MCP.Server/vserver/vserver_fund_holdtest/mcp");
            servers.put("wind-fund-analysis", "https://114.80.154.45/Wind.MCP.Server/vserver/vserver_fund_analysistest/mcp");
        }
    }

    @Data
    public static class AiMarketProperties {
        private String serviceUrl = "";
        private int timeoutSeconds = 60;
        private boolean verifyTls = true;
    }
}
