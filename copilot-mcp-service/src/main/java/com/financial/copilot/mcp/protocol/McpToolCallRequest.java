package com.financial.copilot.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * <h1>MCP tools/call 请求参数对象</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpToolCallRequest {

    /**
     * 要执行的目标工具名
     */
    private String name;

    /**
     * 调用入参键值对
     */
    private Map<String, Object> arguments;
}
