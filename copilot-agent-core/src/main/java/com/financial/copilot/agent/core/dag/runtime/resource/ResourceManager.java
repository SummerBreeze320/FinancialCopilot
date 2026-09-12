package com.financial.copilot.agent.core.dag.runtime.resource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * <h1>声明式物理资源配额管理器 (Resource Manager)</h1>
 * 统一管控 LLM、DPU、RAG、MCP 与 COMPONENT 的并发许可配额，防止瞬时打爆下游接口或数据库。
 */
public class ResourceManager {

    private final Map<ResourceType, Semaphore> semaphores = new ConcurrentHashMap<>();

    public ResourceManager(Map<ResourceType, Integer> limits) {
        if (limits != null) {
            limits.forEach((type, maxPermits) -> {
                int permits = maxPermits != null && maxPermits >= 0 ? maxPermits : 0;
                semaphores.put(type, new Semaphore(permits, true)); // 公平信号量，支持虚拟线程高效挂起
            });
        }
    }

    public static ResourceManager defaultManager() {
        return new ResourceManager(Map.of(
                ResourceType.LLM, 4,
                ResourceType.DPU, 10,
                ResourceType.RAG, 20,
                ResourceType.MCP, 10,
                ResourceType.COMPONENT, 8
        ));
    }

    public boolean tryAcquire(ResourceRequirement requirement) {
        if (requirement == null || requirement.permits() <= 0) {
            return true;
        }
        Semaphore semaphore = semaphores.get(requirement.resourceType());
        return semaphore == null || semaphore.tryAcquire(requirement.permits());
    }

    public void acquire(ResourceRequirement requirement) throws InterruptedException {
        if (requirement == null || requirement.permits() <= 0) {
            return;
        }
        Semaphore semaphore = semaphores.get(requirement.resourceType());
        if (semaphore != null) {
            semaphore.acquire(requirement.permits());
        }
    }

    public void release(ResourceRequirement requirement) {
        if (requirement == null || requirement.permits() <= 0) {
            return;
        }
        Semaphore semaphore = semaphores.get(requirement.resourceType());
        if (semaphore != null) {
            semaphore.release(requirement.permits());
        }
    }

    public int getAvailablePermits(ResourceType type) {
        Semaphore semaphore = semaphores.get(type);
        return semaphore != null ? semaphore.availablePermits() : Integer.MAX_VALUE;
    }
}
