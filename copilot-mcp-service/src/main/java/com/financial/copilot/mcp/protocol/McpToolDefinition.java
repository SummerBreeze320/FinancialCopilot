package com.financial.copilot.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * <h1>MCP 工具描述定义对象 (Tool Definition)</h1>
 * <p>
 * 遵循 Anthropic MCP 协议对 tools/list 返回的工具定义规范，
 * 包含工具名、业务描述以及符合 Draft-07 的 inputSchema。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpToolDefinition {

    /**
     * 工具全局唯一名称（例如: fund_metrics, screen_funds）
     */
    private String name;

    /**
     * 工具功能说明与调用指导
     */
    private String description;

    /**
     * JSON Schema 参数契约对象（Draft-07 规范）
     */
    private Map<String, Object> inputSchema;
}
