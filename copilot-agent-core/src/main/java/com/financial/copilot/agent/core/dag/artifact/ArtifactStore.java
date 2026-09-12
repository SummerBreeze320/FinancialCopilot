package com.financial.copilot.agent.core.dag.artifact;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>多模态强类型投研产物总线 (Artifact Store)</h1>
 * 替代传统弱类型黑板，提供线程安全的强类型产物存取、上游依赖快速聚合与全局上下文隔离。
 */
public class ArtifactStore {

    private final Map<String, Artifact<?>> artifactsByNode = new ConcurrentHashMap<>();
    private final Map<String, Object> globalContext = new ConcurrentHashMap<>();

    /**
     * 存储节点产物
     */
    public void store(String nodeId, Artifact<?> artifact) {
        Objects.requireNonNull(nodeId, "nodeId cannot be null");
        Objects.requireNonNull(artifact, "artifact cannot be null");
        artifactsByNode.put(nodeId, artifact);
    }

    /**
     * 根据节点 ID 获取强类型产物
     */
    @SuppressWarnings("unchecked")
    public <T> Artifact<T> get(String nodeId) {
        return (Artifact<T>) artifactsByNode.get(nodeId);
    }

    /**
     * 按产物类型查找第一个符合条件的产物
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<Artifact<T>> findFirstByType(ArtifactType type) {
        if (type == null) {
            return Optional.empty();
        }
        return artifactsByNode.values().stream()
                .filter(a -> a.type() == type)
                .map(a -> (Artifact<T>) a)
                .findFirst();
    }

    /**
     * 聚合指定上游节点集合的所有产物
     */
    public Map<String, Artifact<?>> getAllUpstream(Set<String> upstreamNodeIds) {
        if (upstreamNodeIds == null || upstreamNodeIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Artifact<?>> result = new HashMap<>();
        for (String id : upstreamNodeIds) {
            Artifact<?> art = artifactsByNode.get(id);
            if (art != null) {
                result.put(id, art);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 获取当前所有产物只读视图
     */
    public Map<String, Artifact<?>> getAllArtifacts() {
        return Collections.unmodifiableMap(artifactsByNode);
    }

    /**
     * 存入全局共享上下文
     */
    public void putGlobalContext(String key, Object value) {
        if (key != null && value != null) {
            globalContext.put(key, value);
        }
    }

    /**
     * 读取全局共享上下文
     */
    public Object getGlobalContext(String key) {
        return key != null ? globalContext.get(key) : null;
    }

    /**
     * 获取所有全局上下文只读视图
     */
    public Map<String, Object> getGlobalContextMap() {
        return Collections.unmodifiableMap(globalContext);
    }

    /**
     * 清空产物
     */
    public void clear() {
        artifactsByNode.clear();
        globalContext.clear();
    }
}
