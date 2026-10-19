package com.financial.copilot.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <h1>JSON-RPC 2.0 响应体对象</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpJsonRpcResponse {

    @Builder.Default
    private String jsonrpc = "2.0";

    /**
     * 对应的请求 ID
     */
    private Object id;

    /**
     * 成功响应结果负载
     */
    private Object result;

    /**
     * 失败异常错误信息
     */
    private McpJsonRpcError error;

    public static McpJsonRpcResponse success(Object id, Object result) {
        return McpJsonRpcResponse.builder()
                .jsonrpc("2.0")
                .id(id)
                .result(result)
                .build();
    }

    public static McpJsonRpcResponse error(Object id, McpJsonRpcError error) {
        return McpJsonRpcResponse.builder()
                .jsonrpc("2.0")
                .id(id)
                .error(error)
                .build();
    }

    public static McpJsonRpcResponse error(Object id, int code, String message) {
        return error(id, new McpJsonRpcError(code, message, null));
    }
}
