package com.financial.copilot.agent.core.dag.model;

import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.runtime.resource.NodePriority;
import com.financial.copilot.agent.core.dag.runtime.resource.ResourceRequirement;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>DAG 执行拓扑节点实体</h1>
 * 封装节点元数据、产物契约、超时、失败重试与物理资源调度策略。
 */
public class GraphNode {

    private final String nodeId;
    private final String taskType;
    private final String name;
    private final Set<ArtifactType> requiredInputs;
    private final ArtifactType outputType;
    private final Map<String, Object> params;
    private final Duration timeout;
    private final FailurePolicy failurePolicy;
    private final int maxRetries;
    private final FallbackProvider fallbackProvider;
    private final ResourceRequirement resourceRequirement;
    private final NodePriority priority;

    private volatile boolean skipped = false;
    private volatile int retryCount = 0;

    public GraphNode(Builder builder) {
        this.nodeId = Objects.requireNonNull(builder.nodeId, "nodeId cannot be null");
        this.taskType = builder.taskType != null ? builder.taskType : "GENERAL";
        this.name = builder.name != null ? builder.name : builder.nodeId;
        this.requiredInputs = builder.requiredInputs != null ? Set.copyOf(builder.requiredInputs) : Set.of();
        this.outputType = builder.outputType != null ? builder.outputType : ArtifactType.GENERAL;
        this.params = new ConcurrentHashMap<>(builder.params != null ? builder.params : Map.of());
        this.timeout = builder.timeout != null ? builder.timeout : Duration.ofMinutes(2);
        this.failurePolicy = builder.failurePolicy != null ? builder.failurePolicy : FailurePolicy.CONTINUE;
        this.maxRetries = builder.maxRetries >= 0 ? builder.maxRetries : 2;
        this.fallbackProvider = builder.fallbackProvider;
        this.resourceRequirement = builder.resourceRequirement != null ? builder.resourceRequirement : ResourceRequirement.none();
        this.priority = builder.priority != null ? builder.priority : NodePriority.NORMAL;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getName() {
        return name;
    }

    public Set<ArtifactType> getRequiredInputs() {
        return requiredInputs;
    }

    public ArtifactType getOutputType() {
        return outputType;
    }

    public Map<String, Object> getParams() {
        return Collections.unmodifiableMap(params);
    }

    public void updateParams(Map<String, Object> newParams) {
        if (newParams != null) {
            this.params.putAll(newParams);
        }
    }

    public Duration getTimeout() {
        return timeout;
    }

    public FailurePolicy getFailurePolicy() {
        return failurePolicy;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public FallbackProvider getFallbackProvider() {
        return fallbackProvider;
    }

    public ResourceRequirement getResourceRequirement() {
        return resourceRequirement;
    }

    public NodePriority getPriority() {
        return priority;
    }

    public boolean isSkipped() {
        return skipped;
    }

    public void markSkipped() {
        this.skipped = true;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }

    public void resetRetryCount() {
        this.retryCount = 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GraphNode graphNode = (GraphNode) o;
        return Objects.equals(nodeId, graphNode.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(nodeId);
    }

    @Override
    public String toString() {
        return "GraphNode{" +
                "nodeId='" + nodeId + '\'' +
                ", name='" + name + '\'' +
                ", taskType='" + taskType + '\'' +
                ", failurePolicy=" + failurePolicy +
                ", priority=" + priority +
                '}';
    }

    public static class Builder {
        private String nodeId;
        private String taskType;
        private String name;
        private Set<ArtifactType> requiredInputs = new HashSet<>();
        private ArtifactType outputType = ArtifactType.GENERAL;
        private Map<String, Object> params = new HashMap<>();
        private Duration timeout = Duration.ofMinutes(2);
        private FailurePolicy failurePolicy = FailurePolicy.CONTINUE;
        private int maxRetries = 2;
        private FallbackProvider fallbackProvider;
        private ResourceRequirement resourceRequirement = ResourceRequirement.none();
        private NodePriority priority = NodePriority.NORMAL;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder taskType(String taskType) {
            this.taskType = taskType;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder requiredInputs(Set<ArtifactType> requiredInputs) {
            this.requiredInputs = requiredInputs;
            return this;
        }

        public Builder outputType(ArtifactType outputType) {
            this.outputType = outputType;
            return this;
        }

        public Builder params(Map<String, Object> params) {
            if (params != null) {
                this.params = new HashMap<>(params);
            }
            return this;
        }

        public Builder param(String key, Object value) {
            this.params.put(key, value);
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder failurePolicy(FailurePolicy failurePolicy) {
            this.failurePolicy = failurePolicy;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder fallbackProvider(FallbackProvider fallbackProvider) {
            this.fallbackProvider = fallbackProvider;
            return this;
        }

        public Builder resourceRequirement(ResourceRequirement resourceRequirement) {
            this.resourceRequirement = resourceRequirement;
            return this;
        }

        public Builder priority(NodePriority priority) {
            this.priority = priority;
            return this;
        }

        public GraphNode build() {
            return new GraphNode(this);
        }
    }
}
