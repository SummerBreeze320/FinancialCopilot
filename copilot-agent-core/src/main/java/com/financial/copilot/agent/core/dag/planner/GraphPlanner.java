package com.financial.copilot.agent.core.dag.planner;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.artifact.payload.FundPool;
import com.financial.copilot.agent.core.dag.artifact.payload.FundResearchResult;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.FailurePolicy;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.model.patch.GraphOperation;
import com.financial.copilot.agent.core.dag.model.patch.GraphPatch;
import com.financial.copilot.agent.core.dag.planner.tool.CapabilityRegistryTool;
import com.financial.copilot.agent.core.dag.planner.tool.MarketMemoryTool;
import com.financial.copilot.agent.core.dag.planner.tool.MetricRAGTool;
import com.financial.copilot.agent.core.dag.planner.tool.SkillRegistryTool;
import com.financial.copilot.agent.core.dag.runtime.RePlanAdvisor;
import com.financial.copilot.agent.core.dag.runtime.resource.NodePriority;
import com.financial.copilot.agent.core.dag.runtime.resource.ResourceRequirement;
import com.financial.copilot.common.enums.AssetCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * <h1>工具增强型动态图规划器 (Tool-Augmented ReAct GraphPlanner)</h1>
 * <p>
 * 装配四大核心 ReAct 工具：
 * <ul>
 *     <li>{@link MetricRAGTool}：金融量化指标语义检索引擎</li>
 *     <li>{@link SkillRegistryTool}：系统专业技能清单注册表</li>
 *     <li>{@link CapabilityRegistryTool}：底层真实金融服务端口能力探测器</li>
 *     <li>{@link MarketMemoryTool}：跨会话长期投资记忆与风险偏好检索器</li>
 * </ul>
 * 承担初次建图 (WHAT/WHY/CONTRACT) 以及运行时动态自适应差分改图 ({@link RePlanAdvisor}) 核心职责。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class GraphPlanner implements RePlanAdvisor {

    private final MetricRAGTool metricRAGTool;
    private final SkillRegistryTool skillRegistryTool;
    private final CapabilityRegistryTool capabilityRegistryTool;
    private final MarketMemoryTool marketMemoryTool;

    public GraphPlanner() {
        this(null, null, null, null);
    }

    public GraphPlanner(MetricRAGTool metricRAGTool, SkillRegistryTool skillRegistryTool) {
        this(metricRAGTool, skillRegistryTool, null, null);
    }

    @Autowired
    public GraphPlanner(
            @Autowired(required = false) MetricRAGTool metricRAGTool,
            @Autowired(required = false) SkillRegistryTool skillRegistryTool,
            @Autowired(required = false) CapabilityRegistryTool capabilityRegistryTool,
            @Autowired(required = false) MarketMemoryTool marketMemoryTool
    ) {
        this.metricRAGTool = metricRAGTool != null ? metricRAGTool : new MetricRAGTool();
        this.skillRegistryTool = skillRegistryTool != null ? skillRegistryTool : new SkillRegistryTool();
        this.capabilityRegistryTool = capabilityRegistryTool != null ? capabilityRegistryTool : new CapabilityRegistryTool();
        this.marketMemoryTool = marketMemoryTool != null ? marketMemoryTool : new MarketMemoryTool();
    }

    /**
     * 根据用户自然语言指令构建初始 ExecutionGraph（不指定会话历史）
     *
     * @param userPrompt 用户自然语言指令
     * @return 初始 DAG 拓扑执行图
     */
    public ExecutionGraph planInitialGraph(String userPrompt) {
        return planInitialGraph(userPrompt, null);
    }

    /**
     * 结合用户自然语言指令与会话历史记忆构建定制化初始 ExecutionGraph
     *
     * @param userPrompt 用户原始提问或复合指令
     * @param sessionId  会话标识 (可选)
     * @return 初始 DAG 拓扑执行图
     */
    public ExecutionGraph planInitialGraph(String userPrompt, String sessionId) {
        String graphId = "graph-" + UUID.randomUUID().toString().substring(0, 8);
        ExecutionGraph graph = new ExecutionGraph(graphId);

        // 1. 金融指标智库语义检索 (Metric RAG)
        MetricRAGTool.MetricSearchResult metrics = metricRAGTool.searchMetrics(userPrompt);

        // 2. 跨会话长期记忆与画像约束召回 (Market Memory)
        List<String> historicalFacts = List.of();
        if (sessionId != null && !sessionId.isBlank()) {
            MarketMemoryTool.MemoryRetrievalResult memory = marketMemoryTool.retrieveMemory(sessionId, userPrompt, 5);
            if (memory != null && memory.relevantFacts() != null) {
                historicalFacts = memory.relevantFacts();
            }
        }

        // 3. 底层金融数据端口能力就绪校验 (Capability Registry)
        boolean fundSupported = capabilityRegistryTool.isAssetCategorySupported(AssetCategory.FUND);
        log.info("[GRAPH-PLANNER] 执行建图探测: FundPort就绪={}, 召回记忆条数={}, 匹配指标数={}",
                fundSupported, historicalFacts.size(), metrics.matchedMetrics().size());

        // 4. 初筛节点
        Map<String, Object> screenParams = new HashMap<>(metrics.recommendedThresholds());
        screenParams.put("metrics", metrics.matchedMetrics());
        if (!historicalFacts.isEmpty()) {
            screenParams.put("historicalPreferences", historicalFacts);
        }
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

        // 5. 深度体检分析节点
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

        // 6. 对标节点
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

        // 7. 研报终审合成节点
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
        if (result == null || graph == null) return null;

        List<GraphOperation> operations = new ArrayList<>();
        String reason = "";

        // 1. 初筛标的池为空 -> 放宽筛选条件动态重试 (ADD_NODE)
        boolean isPoolEmpty = false;
        if (result.type() == ArtifactType.FUND_POOL) {
            if (result.payload() instanceof List<?> list && list.isEmpty()) {
                isPoolEmpty = true;
            } else if (result.payload() instanceof FundPool pool && pool.isEmpty()) {
                isPoolEmpty = true;
            }
        }
        if (isPoolEmpty) {
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

        // 2. 候选标的仅有 1 只 -> 动态将下游双标的对标节点降级为单标的深度剖析节点 (UPDATE_NODE / DOWNGRADE)
        if (result.type() == ArtifactType.FUND_RESEARCH && result.payload() instanceof FundResearchResult research) {
            List<String> candidates = research.topCandidates();
            if (candidates != null && candidates.size() == 1) {
                String singleCode = candidates.get(0);
                Set<String> downstreams = graph.getDownstream(completedNodeId);
                for (String downId : downstreams) {
                    GraphNode downNode = graph.getNode(downId);
                    if (downNode != null && "COMPARISON".equalsIgnoreCase(downNode.getTaskType())) {
                        Map<String, Object> updateParams = new HashMap<>(downNode.getParams());
                        updateParams.put("taskType", "DEEP_DIVE");
                        updateParams.put("name", "单一最优标的穿透式深度剖析");
                        updateParams.put("downgradedToSingle", true);
                        updateParams.put("targetCode", singleCode);
                        operations.add(GraphOperation.updateNode(downId, updateParams));
                        reason = "候选标的仅有1只(" + singleCode + ")，动态将下游双标的对标节点降级为单标的深度剖析节点";
                    }
                }
            }
        }

        // 3. 证据链不足 missingEvidence -> 动态插入补数节点 (ADD_NODE)
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
            if (reason.isBlank()) {
                reason = "证据契约声明缺失数据，动态插入精准补数节点: " + missing;
            } else {
                reason += "; 补充缺失证据节点: " + missing;
            }
        }

        if (operations.isEmpty()) {
            return null;
        }

        return new GraphPatch(graph.getRevision(), operations, reason);
    }

    public MetricRAGTool getMetricRAGTool() {
        return metricRAGTool;
    }

    public SkillRegistryTool getSkillRegistryTool() {
        return skillRegistryTool;
    }

    public CapabilityRegistryTool getCapabilityRegistryTool() {
        return capabilityRegistryTool;
    }

    public MarketMemoryTool getMarketMemoryTool() {
        return marketMemoryTool;
    }
}
