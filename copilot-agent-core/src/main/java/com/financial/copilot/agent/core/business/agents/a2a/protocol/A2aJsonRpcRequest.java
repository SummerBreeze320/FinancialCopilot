package com.financial.copilot.agent.core.business.agents.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** A2A JSON-RPC 2.0 请求载荷 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aJsonRpcRequest(
        String jsonrpc,
        Object id,
        String method,
        Map<String, Object> params
) {
    public A2aJsonRpcRequest {
        jsonrpc = jsonrpc != null ? jsonrpc : "2.0";
    }
}
