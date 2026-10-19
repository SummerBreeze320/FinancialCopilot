package com.financial.copilot.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <h1>JSON-RPC 2.0 错误体对象</h1>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpJsonRpcError {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    /**
     * 错误码
     */
    private int code;

    /**
     * 简短错误描述
     */
    private String message;

    /**
     * 额外错误上下文数据
     */
    private Object data;

    public static McpJsonRpcError methodNotFound(String method) {
        return new McpJsonRpcError(METHOD_NOT_FOUND, "Method not found: " + method, null);
    }

    public static McpJsonRpcError invalidParams(String reason) {
        return new McpJsonRpcError(INVALID_PARAMS, "Invalid params: " + reason, null);
    }

    public static McpJsonRpcError internalError(String message) {
        return new McpJsonRpcError(INTERNAL_ERROR, message != null ? message : "Internal error", null);
    }
}
