package com.financial.copilot.agent.core.dag.planner;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.FailurePolicy;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.model.patch.GraphOperation;
import com.financial.copilot.agent.core.dag.model.patch.GraphPatch;
import com.financial.copilot.agent.core.dag.planner.tool.MetricRAGTool;
import com.financial.copilot.agent.core.dag.planner.tool.SkillRegistryTool;
import com.financial.copilot.agent.core.dag.runtime.RePlanAdvisor;
import com.financial.copilot.agent.core.dag.runtime.resource.NodePriority;
import com.financial.copilot.agent.core.dag.runtime.resource.ResourceRequirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * <h1>工具增强型动态图规划器 (Tool-Augmented ReAct GraphPlanner)</h1>
 * <p>
 * 装配 {@link MetricRAGTool} 与 {@link SkillRegistryTool}，支持用户指令初次建图以及运行时
 * 动态自适应差分改图 ({@link RePlanAdvisor})。
 * </p>
 */
@Component
public class GraphPlanner implements RePlanAdvisor {

    private static final Logger log = LoggerFactory.getLogger(GraphPlanner.class);

    private final MetricRAGTool metricRAGTool;
    private final SkillRegistryTool skillRegistryTool;

    public GraphPlanner(MetricRAGTool metricRAGTool, SkillRegistryTool skillRegistryTool) {
        this.metricRAGTool = metricRAGTool != null ? metricRAGTool : new MetricRAGTool();
        this.skillRegistryTool = skillRegistryTool != null ? skillRegistryTool : new SkillRegistryTool();
    }

    /**
     * 根据用户自然语言指令构建初始 ExecutionGraph
     */
    public ExecutionGraph planInitialGraph(String userPrompt) {
        String graphId = "graph-" + UUID.randomUUID().toString().substring(0, 8);
        ExecutionGraph graph = new ExecutionGraph(graphId);

        MetricRAGTool.MetricSearchResult metrics = metricRAGTool.searchMetrics(userPrompt);

        // 1. 初筛节点
        Map<String, Object> screenParams = new HashMap<>(metrics.recommendedThresholds());
        screenParams.put("metrics", metrics.matchedMetrics());
        GraphNode screenNode = GraphNode.builder()
                .nodeId("step-1-screening")
                .name("标的多维量化初筛")
                .taskType("SCREENING")
                .outputType(ArtifactType.FUND_POOL)
                .resourceRequirement(ResourceRequirement.dpu())
                .failurePolicy(FailurePolicy.CONTINUE)
                .params(screenParams)
                .build();
        graph.addNode(screenNode);

        // 2. 深度分析节点
        GraphNode analysisNode = GraphNode.builder()
                .nodeId("step-2-analysis")
                .name("候选标的量化体检打分")
                .taskType("BATCH_ANALYSIS")
                .outputType(ArtifactType.FUND_RESEARCH)
                .resourceRequirement(ResourceRequirement.dpu())
                .failurePolicy(FailurePolicy.CONTINUE)
                .build();
        graph.addNode(analysisNode);
        graph.addEdge("step-1-screening", "step-2-analysis");

        // 3. 对标节点
        GraphNode comparisonNode = GraphNode.builder()
                .nodeId("step-3-comparison")
                .name("决赛圈标的深度横向对标")
                .taskType("COMPARISON")
                .outputType(ArtifactType.COMPARISON_REPORT)
                .resourceRequirement(ResourceRequirement.llm())
                .failurePolicy(FailurePolicy.CONTINUE)
                .build();
        graph.addNode(comparisonNode);
        graph.addEdge("step-2-analysis", "step-3-comparison");

        // 4. 研报终审合成节点
        GraphNode synthesisNode = GraphNode.builder()
                .nodeId("step-4-synthesis")
                .name("专业投资研报终审合成")
                .taskType("SYNTHESIS")
                .outputType(ArtifactType.FINAL_REPORT)
                .priority(NodePriority.HIGH)
                .resourceRequirement(ResourceRequirement.llm())
                .failurePolicy(FailurePolicy.FAIL_FAST)
                .build();
        graph.addNode(synthesisNode);
        graph.addEdge("step-3-comparison", "step-4-synthesis");

        return graph;
    }

    @Override
    public GraphPatch planPatch(ExecutionGraph graph, String completedNodeId, Artifact<?> result) {
        if (result == null) return null;

        List<GraphOperation> operations = new ArrayList<>();
        String reason = "";

        // 1. 初筛标的池为空 -> 放宽筛选条件动态重试
        if (result.type() == ArtifactType.FUND_POOL && result.payload() instanceof List<?> list && list.isEmpty()) {
            String retryNodeId = completedNodeId + "-relaxed";
            GraphNode retryNode = GraphNode.builder()
                    .nodeId(retryNodeId)
                    .name("放宽条件筛选重试")
                    .taskType("SCREENING")
                    .outputType(ArtifactType.FUND_POOL)
                    .resourceRequirement(ResourceRequirement.dpu())
                    .failurePolicy(FailurePolicy.CONTINUE)
                    .params(Map.of("relaxed", true))
                    .build();

            operations.add(GraphOperation.addNode(retryNode));
            // 将重试节点连接至原节点的直接下游
            for (String down : graph.getDownstream(completedNodeId)) {
                operations.add(GraphOperation.addEdge(retryNodeId, down));
            }
            reason = "初筛池命中数量为0，动态插入放宽条件重试节点";
        }

        // 2. 证据链不足 missingEvidence -> 动态插入补数节点
        if (result.evidenceContract() != null && !result.evidenceContract().isSufficient()) {
            List<String> missing = result.evidenceContract().missingEvidence();
            for (String evidenceKey : missing) {
                String supplementNodeId = "supplement-" + evidenceKey;
                GraphNode supplementNode = GraphNode.builder()
                        .nodeId(supplementNodeId)
                        .name("数据补充检索: " + evidenceKey)
                        .taskType("DATA_RAW")
                        .outputType(ArtifactType.DOCUMENT_EVIDENCE)
                        .resourceRequirement(ResourceRequirement.rag())
                        .failurePolicy(FailurePolicy.CONTINUE)
                        .params(Map.of("targetEvidence", evidenceKey))
                        .build();

                operations.add(GraphOperation.addNode(supplementNode));
                for (String down : graph.getDownstream(completedNodeId)) {
                    operations.add(GraphOperation.addEdge(supplementNodeId, down));
                }
            }
            reason = "证据契约声明缺失数据，动态插入精准补数节点: " + missing;
        }

        if (operations.isEmpty()) {
            return null;
        }

        return new GraphPatch(graph.getRevision(), operations, reason);
    }
}
