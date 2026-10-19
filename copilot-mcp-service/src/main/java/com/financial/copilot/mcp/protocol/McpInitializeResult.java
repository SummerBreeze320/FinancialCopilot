package com.financial.copilot.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * <h1>MCP 握手初始化结果对象</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpInitializeResult {

    @Builder.Default
    private String protocolVersion = "2024-11-05";

    private McpCapabilities capabilities;

    private McpServerInfo serverInfo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class McpServerInfo {
        private String name;
        private String version;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class McpCapabilities {
        private McpToolsCapability tools;
        private Map<String, Object> experimental;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class McpToolsCapability {
        @Builder.Default
        private Boolean listChanged = true;
    }

    public static McpInitializeResult standard(String serverName, String serverVersion) {
        return McpInitializeResult.builder()
                .protocolVersion("2024-11-05")
                .serverInfo(new McpServerInfo(serverName, serverVersion))
                .capabilities(McpCapabilities.builder()
                        .tools(new McpToolsCapability(true))
                        .build())
                .build();
    }
}
