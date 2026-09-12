package com.financial.copilot.agent.core.dag.runtime.resource;

/**
 * <h1>物理/逻辑受限资源类型枚举</h1>
 */
public enum ResourceType {
    /** 大模型推理调用配额 (默认4) */
    LLM,
    /** 数值计算与密集量化分析单元 (默认10) */
    DPU,
    /** 向量知识库与研报检索并发 (默认20) */
    RAG,
    /** 外部 MCP 工具与爬虫 I/O 并发 (默认10) */
    MCP,
    /** 前端卡片与交互组件渲染并发 (默认8) */
    COMPONENT
}
