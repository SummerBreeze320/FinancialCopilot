package com.financial.copilot.agent.core.infra.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * <h1>MCP 工具元数据信息</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpToolInfo {

    /**
     * 工具全局唯一名称
     */
    private String name;

    /**
     * 工具功能描述
     */
    private String description;

    /**
     * Draft-07 JSON Schema 参数结构
     */
    private Map<String, Object> inputSchema;
}
