package com.financial.copilot.mcp.registry;

import com.financial.copilot.mcp.protocol.McpToolCallResult;
import com.financial.copilot.mcp.protocol.McpToolDefinition;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * <h1>MCP 工具调用处理器接口</h1>
 * <p>
 * 提供工具元数据定义与非阻塞式响应式执行能力。
 * </p>
 *
 * @author FinancialCopilot
 */
public interface McpToolHandler {

    /**
     * 获取工具的元数据规范（名称、业务描述、JSON Schema 入参规范）
     *
     * @return MCP 工具定义对象
     */
    McpToolDefinition getDefinition();

    /**
     * 响应式执行工具调用
     *
     * @param arguments 调用实参字典
     * @return 包含返回内容与错误标志的 Mono 响应
     */
    Mono<McpToolCallResult> execute(Map<String, Object> arguments);
}
