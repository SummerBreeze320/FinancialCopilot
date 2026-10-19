package com.financial.copilot.mcp.protocol;

/**
 * <h1>MCP 协议标准方法名常量</h1>
 * <p>
 * 遵循 Anthropic Model Context Protocol (MCP) 2024-11-05 标准方法名。
 * </p>
 *
 * @author FinancialCopilot
 */
public final class McpMethod {

    private McpMethod() {}

    /**
     * 客户端握手初始化请求
     */
    public static final String INITIALIZE = "initialize";

    /**
     * 客户端确认初始化就绪通知
     */
    public static final String NOTIFICATIONS_INITIALIZED = "notifications/initialized";

    /**
     * 枚举可用工具列表请求
     */
    public static final String TOOLS_LIST = "tools/list";

    /**
     * 调用指定工具执行请求
     */
    public static final String TOOLS_CALL = "tools/call";

    /**
     * 心跳健康检查请求
     */
    public static final String PING = "ping";
}
