package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.model.InputBinding;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.model.NodeStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;

/**
 * <h1>DAG 依赖拓扑判定裁决器</h1>
 * 纯内存、纳秒级判定前驱依赖是否全部就绪放行。
 */
public class DependencyResolver {

    public static NodeInput resolve(GraphNode node, ArtifactStore store) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Artifact<?>> artifacts = new LinkedHashMap<>();
        for (InputBinding binding : node.getInputBindings()) {
            Artifact<?> artifact = store.get(binding.producerNodeId());
            if (artifact == null) {
                if (binding.required()) throw new IllegalStateException("Required producer artifact is missing: " + binding.producerNodeId());
                continue;
            }
            if (artifact.type() != binding.expectedType()) {
                throw new IllegalStateException("Binding " + binding.name() + " expected " + binding.expectedType()
                        + " from " + binding.producerNodeId() + " but received " + artifact.type());
            }
            Object value = project(artifact.payload(), binding.path());
            if (value == null && binding.required()) {
                throw new IllegalStateException("Required binding path is missing: " + binding.path());
            }
            if (value != null) values.put(binding.name(), value);
            artifacts.put(binding.name(), artifact);
        }
        return new NodeInput(values, artifacts);
    }

    private static Object project(Object value, String path) {
        if ("$".equals(path)) return value;
        Object current = value;
        for (String field : path.substring(2).split("\\.")) {
            if (current == null) return null;
            if (current instanceof Map<?, ?> map) {
                current = map.get(field);
                continue;
            }
            current = readProperty(current, field);
        }
        return current;
    }

    private static Object readProperty(Object target, String field) {
        try {
            if (target.getClass().isRecord()) {
                for (RecordComponent component : target.getClass().getRecordComponents()) {
                    if (component.getName().equals(field)) {
                        Method accessor = component.getAccessor();
                        accessor.trySetAccessible();
                        return accessor.invoke(target);
                    }
                }
                return null;
            }
            String suffix = Character.toUpperCase(field.charAt(0)) + field.substring(1);
            Method getter;
            try {
                getter = target.getClass().getMethod("get" + suffix);
            } catch (NoSuchMethodException ignored) {
                getter = target.getClass().getMethod("is" + suffix);
            }
            getter.trySetAccessible();
            return getter.invoke(target);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read binding field " + field, e);
        }
    }

    /**
     * 判断目标节点的前驱依赖是否已全部处于终止成功或跳过状态
     */
    public static boolean isReady(String nodeId, ExecutionGraph graph, Function<String, NodeStatus> statusProvider) {
        Set<String> upstreams = graph.getUpstream(nodeId);
        if (upstreams.isEmpty()) {
            return true;
        }
        for (String upId : upstreams) {
            NodeStatus s = statusProvider.apply(upId);
            if (s != NodeStatus.SUCCEEDED && s != NodeStatus.SKIPPED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 当某节点完成后，检索其所有直接下游中当前已完全就绪 (可进入 READY) 的节点列表
     */
    public static List<String> findReadyChildren(String completedNodeId, ExecutionGraph graph, Function<String, NodeStatus> statusProvider) {
        List<String> readyChildren = new ArrayList<>();
        Set<String> downstreams = graph.getDownstream(completedNodeId);

        for (String childId : downstreams) {
            NodeStatus currentStatus = statusProvider.apply(childId);
            if (currentStatus == NodeStatus.PENDING) {
                if (isReady(childId, graph, statusProvider)) {
                    readyChildren.add(childId);
                }
            }
        }
        return readyChildren;
    }
}
