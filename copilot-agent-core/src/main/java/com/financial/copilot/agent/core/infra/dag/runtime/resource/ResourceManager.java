package com.financial.copilot.agent.core.infra.dag.runtime.resource;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

/**
 * <h1>声明式物理资源配额管理器 (Resource Manager)</h1>
 * 统一管控 LLM、DPU、RAG、MCP 与 COMPONENT 的并发许可配额，防止瞬时打爆下游接口或数据库。
 */
@Slf4j
public class ResourceManager {

    private final Map<ResourceType, Semaphore> semaphores = new ConcurrentHashMap<>();
    private final List<Runnable> releaseListeners = new CopyOnWriteArrayList<>();


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
                ResourceType.AGENT, 8,
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

    public synchronized boolean tryAcquire(Map<ResourceType, Integer> requirements) {
        if (requirements == null || requirements.isEmpty()) return true;
        for (ResourceType type : ResourceType.values()) {
            int needed = Math.max(0, requirements.getOrDefault(type, 0));
            Semaphore semaphore = semaphores.get(type);
            if (semaphore != null && semaphore.availablePermits() < needed) return false;
        }
        for (ResourceType type : ResourceType.values()) {
            int needed = Math.max(0, requirements.getOrDefault(type, 0));
            Semaphore semaphore = semaphores.get(type);
            if (semaphore != null && needed > 0 && !semaphore.tryAcquire(needed)) {
                throw new IllegalStateException("Resource availability changed while locked");
            }
        }
        return true;
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
            notifyReleaseListeners();
        }
    }

    public synchronized void release(Map<ResourceType, Integer> requirements) {
        if (requirements == null) return;
        boolean released = false;
        for (Map.Entry<ResourceType, Integer> entry : requirements.entrySet()) {
            ResourceType type = entry.getKey();
            Integer permits = entry.getValue();
            Semaphore semaphore = semaphores.get(type);
            if (semaphore != null && permits != null && permits > 0) {
                semaphore.release(permits);
                released = true;
            }
        }
        if (released) {
            notifyReleaseListeners();
        }
    }

    /**
     * 注册资源释放监听器，当任何受限资源许可被释放时触发回调
     *
     * @param listener 释放回调
     */
    public void onResourceReleased(Runnable listener) {
        if (listener != null) {
            releaseListeners.add(listener);
        }
    }

    private void notifyReleaseListeners() {
        for (Runnable listener : releaseListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                log.warn("Error notifying resource release listener: {}", e.getMessage(), e);
            }
        }
    }


    public int getAvailablePermits(ResourceType type) {
        Semaphore semaphore = semaphores.get(type);
        return semaphore != null ? semaphore.availablePermits() : Integer.MAX_VALUE;
    }
}
