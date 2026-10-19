package com.financial.copilot.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <h1>JSON-RPC 2.0 请求体对象</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpJsonRpcRequest {

    @Builder.Default
    private String jsonrpc = "2.0";

    /**
     * 请求唯一标识，若为通知则为 null
     */
    private Object id;

    /**
     * MCP 协议方法名
     */
    private String method;

    /**
     * 原始参数树
     */
    private JsonNode params;

    /**
     * 判断当前请求是否属于不需要响应的通知
     *
     * @return true 若为通知
     */
    public boolean isNotification() {
        return id == null;
    }
}
