package com.financial.copilot.agent.core.business.agents.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A2A JSON-RPC 2.0 响应载荷 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aJsonRpcResponse(
        String jsonrpc,
        Object id,
        Object result,
        A2aJsonRpcError error
) {
    public static A2aJsonRpcResponse success(Object id, Object result) {
        return new A2aJsonRpcResponse("2.0", id, result, null);
    }

    public static A2aJsonRpcResponse error(Object id, int code, String message) {
        return new A2aJsonRpcResponse("2.0", id, null, new A2aJsonRpcError(code, message));
    }

    public record A2aJsonRpcError(int code, String message) {}
}
