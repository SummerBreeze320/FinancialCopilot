package com.financial.copilot.agent.core.dag.runtime.resource;

/**
 * <h1>DAG 节点资源需求描述契约</h1>
 *
 * @param type    受限资源类型 (LLM, DPU, RAG, MCP, COMPONENT)
 * @param permits 所需信号量许可配额数
 */
public record ResourceRequirement(
    ResourceType type,
    int permits
) {
    public static ResourceRequirement of(ResourceType type, int permits) {
        return new ResourceRequirement(type, permits);
    }

    public ResourceType resourceType() {
        return type;
    }

    public static ResourceRequirement none() {
        return new ResourceRequirement(ResourceType.LLM, 0);
    }

    public static ResourceRequirement llm() {
        return new ResourceRequirement(ResourceType.LLM, 1);
    }

    public static ResourceRequirement llm(int permits) {
        return new ResourceRequirement(ResourceType.LLM, permits);
    }

    public static ResourceRequirement dpu() {
        return new ResourceRequirement(ResourceType.DPU, 1);
    }

    public static ResourceRequirement dpu(int permits) {
        return new ResourceRequirement(ResourceType.DPU, permits);
    }

    public static ResourceRequirement rag() {
        return new ResourceRequirement(ResourceType.RAG, 1);
    }

    public static ResourceRequirement rag(int permits) {
        return new ResourceRequirement(ResourceType.RAG, permits);
    }

    public static ResourceRequirement mcp() {
        return new ResourceRequirement(ResourceType.MCP, 1);
    }

    public static ResourceRequirement mcp(int permits) {
        return new ResourceRequirement(ResourceType.MCP, permits);
    }

    public static ResourceRequirement component() {
        return new ResourceRequirement(ResourceType.COMPONENT, 1);
    }

    public static ResourceRequirement component(int permits) {
        return new ResourceRequirement(ResourceType.COMPONENT, permits);
    }
}
