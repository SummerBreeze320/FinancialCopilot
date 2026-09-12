package com.financial.copilot.agent.core.dag.adapter;

import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.FailurePolicy;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.resource.NodePriority;
import com.financial.copilot.agent.core.dag.runtime.resource.ResourceRequirement;
import com.financial.copilot.agent.core.pipeline.ExecutionPlan;
import com.financial.copilot.agent.core.pipeline.SubTask;

import java.util.*;

/**
 * <h1>旧版任务执行计划适配器 (LegacyPlanAdapter)</h1>
 * <p>
 * 将旧版线性或粗粒度的 {@link ExecutionPlan} 及其 {@link SubTask} 列表无缝转换为
 * 新版依赖驱动的 {@link ExecutionGraph}，保留全部任务元数据并赋予自适应资源规格与调度优先级。
 * </p>
 */
public class LegacyPlanAdapter {

    public static final String META_KEY_SUBTASK = "subTask";
    public static final String META_KEY_TASK_TYPE = "taskType";
    public static final String META_KEY_STEP_INDEX = "stepIndex";
    public static final String META_KEY_DESCRIPTION = "description";
    public static final String META_KEY_INPUT_KEY = "inputKey";
    public static final String META_KEY_OUTPUT_KEY = "outputKey";

    /**
     * 将 ExecutionPlan 转换为标准 ExecutionGraph
     *
     * @param plan 原始执行计划
     * @return 转换后的 DAG 执行图
     */
    public static ExecutionGraph toExecutionGraph(ExecutionPlan plan) {
        Objects.requireNonNull(plan, "ExecutionPlan cannot be null");
        String graphId = "graph-" + UUID.randomUUID().toString().substring(0, 8);
        ExecutionGraph graph = new ExecutionGraph(graphId);

        List<SubTask> steps = plan.getSteps();
        if (steps == null || steps.isEmpty()) {
            return graph;
        }

        // 检查所有后序步骤是否全部缺失显式依赖（兼容旧版纯线性计划）
        boolean allEmptyDependencies = steps.size() > 1 && steps.stream()
                .skip(1)
                .allMatch(s -> s.getDependsOn() == null || s.getDependsOn().isEmpty());

        Map<String, Set<String>> edgesToAdd = new LinkedHashMap<>();

        for (int i = 0; i < steps.size(); i++) {
            SubTask step = steps.get(i);
            int stepIndex = step.getStepIndex() > 0 ? step.getStepIndex() : (i + 1);
            String nodeId = "step-" + stepIndex;

            Set<String> dependencies = new HashSet<>();
            if (step.getDependsOn() != null && !step.getDependsOn().isEmpty()) {
                for (Integer dep : step.getDependsOn()) {
                    dependencies.add("step-" + dep);
                }
            } else if (allEmptyDependencies && i > 0) {
                // 自动推断前置依赖为上一步骤
                SubTask prevStep = steps.get(i - 1);
                int prevIndex = prevStep.getStepIndex() > 0 ? prevStep.getStepIndex() : i;
                dependencies.add("step-" + prevIndex);
            }

            edgesToAdd.put(nodeId, dependencies);

            NodePriority priority = resolvePriority(step.getTaskType());
            ResourceRequirement resourceRequirement = resolveResourceRequirement(step.getTaskType());
            ArtifactType outputType = resolveOutputType(step.getTaskType());

            Map<String, Object> params = new HashMap<>();
            if (step != null) {
                params.put(META_KEY_SUBTASK, step);
            }
            if (step.getTaskType() != null) {
                params.put(META_KEY_TASK_TYPE, step.getTaskType());
            }
            params.put(META_KEY_STEP_INDEX, stepIndex);
            if (step.getDescription() != null) {
                params.put(META_KEY_DESCRIPTION, step.getDescription());
            }
            if (step.getInputKey() != null) {
                params.put(META_KEY_INPUT_KEY, step.getInputKey());
            }
            if (step.getOutputKey() != null) {
                params.put(META_KEY_OUTPUT_KEY, step.getOutputKey());
            }
            if (step.getParams() != null) {
                step.getParams().forEach((k, v) -> {
                    if (k != null && v != null) {
                        params.put(k, v);
                    }
                });
            }

            GraphNode node = GraphNode.builder()
                    .nodeId(nodeId)
                    .name(step.getTaskType() != null ? step.getTaskType() : nodeId)
                    .taskType(step.getTaskType() != null ? step.getTaskType() : "GENERAL")
                    .outputType(outputType)
                    .priority(priority)
                    .resourceRequirement(resourceRequirement)
                    .failurePolicy(FailurePolicy.FAIL_FAST)
                    .params(params)
                    .build();

            graph.addNode(node);
        }

        // 添加依赖拓扑边
        for (Map.Entry<String, Set<String>> entry : edgesToAdd.entrySet()) {
            String toNode = entry.getKey();
            for (String fromNode : entry.getValue()) {
                if (graph.getNode(fromNode) != null) {
                    graph.addEdge(fromNode, toNode);
                }
            }
        }

        return graph;
    }

    /**
     * 从图节点提取 SubTask 实例
     */
    public static SubTask extractSubTask(GraphNode node, ExecutionGraph graph) {
        if (node == null) return null;
        Object subTaskObj = node.getParams().get(META_KEY_SUBTASK);
        if (subTaskObj instanceof SubTask subTask) {
            return subTask;
        }

        // 若未直接存储，则依据 params 还原
        int stepIndex = 1;
        Object idx = node.getParams().get(META_KEY_STEP_INDEX);
        if (idx instanceof Number n) {
            stepIndex = n.intValue();
        } else if (node.getNodeId().startsWith("step-")) {
            try {
                stepIndex = Integer.parseInt(node.getNodeId().substring(5));
            } catch (Exception ignored) {}
        }

        List<Integer> dependsOn = new ArrayList<>();
        if (graph != null) {
            Set<String> upstreams = graph.getUpstream(node.getNodeId());
            for (String dep : upstreams) {
                if (dep.startsWith("step-")) {
                    try {
                        dependsOn.add(Integer.parseInt(dep.substring(5)));
                    } catch (Exception ignored) {}
                }
            }
        }

        return SubTask.builder()
                .stepIndex(stepIndex)
                .taskType((String) node.getParams().getOrDefault(META_KEY_TASK_TYPE, node.getTaskType()))
                .description((String) node.getParams().getOrDefault(META_KEY_DESCRIPTION, node.getName()))
                .dependsOn(dependsOn)
                .inputKey((String) node.getParams().get(META_KEY_INPUT_KEY))
                .outputKey((String) node.getParams().get(META_KEY_OUTPUT_KEY))
                .build();
    }

    private static NodePriority resolvePriority(String taskType) {
        if (taskType == null) return NodePriority.NORMAL;
        if ("SYNTHESIS".equalsIgnoreCase(taskType)) {
            return NodePriority.HIGH;
        }
        return NodePriority.NORMAL;
    }

    private static ResourceRequirement resolveResourceRequirement(String taskType) {
        if (taskType == null) return ResourceRequirement.none();
        return switch (taskType.toUpperCase()) {
            case "SCREENING", "BATCH_ANALYSIS" -> ResourceRequirement.dpu();
            case "COMPARISON", "SYNTHESIS" -> ResourceRequirement.llm();
            default -> ResourceRequirement.none();
        };
    }

    private static ArtifactType resolveOutputType(String taskType) {
        if (taskType == null) return ArtifactType.GENERAL;
        return switch (taskType.toUpperCase()) {
            case "SCREENING" -> ArtifactType.FUND_POOL;
            case "BATCH_ANALYSIS" -> ArtifactType.FUND_RESEARCH;
            case "COMPARISON" -> ArtifactType.COMPARISON_REPORT;
            case "SYNTHESIS" -> ArtifactType.FINAL_REPORT;
            default -> ArtifactType.GENERAL;
        };
    }
}
