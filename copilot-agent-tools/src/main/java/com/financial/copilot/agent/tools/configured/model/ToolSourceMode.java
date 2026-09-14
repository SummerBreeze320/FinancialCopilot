package com.financial.copilot.agent.tools.configured.model;

/**
 * Tool 来源模式枚举。
 * 区分系统使用本地数据库原生计算还是外部配置化协议接口。
 */
public enum ToolSourceMode {
    /** 本地工程原生 Tool (PostgreSQL, Neo4j, PGVector, 本地金融计算引擎) */
    LOCAL_PROJECT,
    /** 外部配置化 Tool (Wind HTTP Invoke, MCP JSON-RPC, Expo) */
    EXTERNAL_CONFIGURED
}
