package com.financial.copilot.agent.core.dag.planner;

import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.model.*;
import com.financial.copilot.agent.core.dag.planner.tool.*;
import com.financial.copilot.agent.core.dag.runtime.resource.*;
import java.util.*;

/** Stable fallback that produces the same executable contract without an LLM. */
public class DeterministicGraphPlanner {
    private final MetricRAGTool metrics;
    private final CapabilityRegistryTool capabilities;
    private final MarketMemoryTool memory;

    public DeterministicGraphPlanner(MetricRAGTool metrics, CapabilityRegistryTool capabilities, MarketMemoryTool memory) {
        this.metrics = metrics;
        this.capabilities = capabilities;
        this.memory = memory;
    }

    public ExecutionGraph plan(GraphPlanningRequest request) {
        String prompt = request.prompt() == null ? "" : request.prompt();
        List<String> themes = List.of("半导体", "新能源", "医药", "消费").stream().filter(prompt::contains).toList();
        boolean macro = prompt.contains("宏观") || prompt.contains("流动性");
        if (themes.size() >= 2 || (macro && !themes.isEmpty())) return parallel(request, themes, macro);
        return standard(request);
    }

    private ExecutionGraph standard(GraphPlanningRequest request) {
        ExecutionGraph graph = new ExecutionGraph("graph-" + UUID.randomUUID().toString().substring(0, 8));
        MetricRAGTool.MetricSearchResult found = metrics.searchMetrics(request.prompt());
        Map<String, Object> params = new HashMap<>(found.recommendedThresholds());
        params.put("metrics", found.matchedMetrics());
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            MarketMemoryTool.MemoryRetrievalResult recalled = memory.retrieveMemory(request.sessionId(), request.prompt(), 5);
            if (recalled != null && recalled.relevantFacts() != null && !recalled.relevantFacts().isEmpty()) {
                params.put("historicalPreferences", recalled.relevantFacts());
            }
        }
        capabilities.isAssetCategorySupported(com.financial.copilot.common.enums.AssetCategory.FUND);
        GraphNode screen = node("step-1-screening", "标的多维量化初筛", "SCREENING", ArtifactType.FUND_POOL, params, ResourceRequirement.dpu());
        GraphNode analysis = GraphNode.builder().nodeId("step-2-analysis").name("候选标的量化体检打分")
                .taskType("BATCH_ANALYSIS").outputType(ArtifactType.FUND_RESEARCH)
                .inputBindings(List.of(new InputBinding("funds", screen.getNodeId(), ArtifactType.FUND_POOL, "$", true)))
                .resourceRequirement(ResourceRequirement.dpu()).failurePolicy(FailurePolicy.CONTINUE).build();
        GraphNode comparison = GraphNode.builder().nodeId("step-3-comparison").name("决赛圈标的深度横向对标")
                .taskType("COMPARISON").outputType(ArtifactType.COMPARISON_REPORT)
                .inputBindings(List.of(new InputBinding("research", analysis.getNodeId(), ArtifactType.FUND_RESEARCH, "$", true)))
                .resourceRequirement(ResourceRequirement.llm()).failurePolicy(FailurePolicy.CONTINUE).build();
        GraphNode synthesis = GraphNode.builder().nodeId("step-4-synthesis").name("专业投资研报终审合成")
                .taskType("SYNTHESIS").outputType(ArtifactType.FINAL_REPORT)
                .inputBindings(List.of(new InputBinding("comparison", comparison.getNodeId(), ArtifactType.COMPARISON_REPORT, "$", true)))
                .priority(NodePriority.HIGH).resourceRequirement(ResourceRequirement.llm())
                .failurePolicy(FailurePolicy.FAIL_FAST).build();
        List.of(screen, analysis, comparison, synthesis).forEach(graph::addNode);
        graph.addEdge(screen.getNodeId(), analysis.getNodeId());
        graph.addEdge(analysis.getNodeId(), comparison.getNodeId());
        graph.addEdge(comparison.getNodeId(), synthesis.getNodeId());
        return graph;
    }

    private ExecutionGraph parallel(GraphPlanningRequest request, List<String> themes, boolean includeMacro) {
        ExecutionGraph graph = new ExecutionGraph("graph-" + UUID.randomUUID().toString().substring(0, 8));
        List<GraphNode> analyses = new ArrayList<>();
        List<String> effectiveThemes = themes.isEmpty() ? List.of("综合") : themes;
        for (int i = 0; i < effectiveThemes.size(); i++) {
            String theme = effectiveThemes.get(i);
            GraphNode screen = node("screen-theme-" + i, theme + "基金初筛", "SCREENING", ArtifactType.FUND_POOL,
                    Map.of("theme", theme), ResourceRequirement.dpu());
            GraphNode analysis = GraphNode.builder().nodeId("analysis-theme-" + i).name(theme + "基金研究")
                    .taskType("BATCH_ANALYSIS").outputType(ArtifactType.FUND_RESEARCH)
                    .inputBindings(List.of(new InputBinding("funds", screen.getNodeId(), ArtifactType.FUND_POOL, "$", true)))
                    .resourceRequirement(ResourceRequirement.dpu()).failurePolicy(FailurePolicy.CONTINUE).build();
            graph.addNode(screen); graph.addNode(analysis); graph.addEdge(screen.getNodeId(), analysis.getNodeId());
            analyses.add(analysis);
        }
        GraphNode macro = null;
        if (includeMacro) {
            macro = node("macro", "宏观环境分析", "MACRO", ArtifactType.MACRO_FACTS, Map.of(), ResourceRequirement.rag());
            graph.addNode(macro);
        }
        List<InputBinding> comparisonInputs = new ArrayList<>();
        for (int i = 0; i < analyses.size(); i++) comparisonInputs.add(new InputBinding(
                "research" + i, analyses.get(i).getNodeId(), ArtifactType.FUND_RESEARCH, "$", true));
        GraphNode comparison = GraphNode.builder().nodeId("comparison").name("跨主题基金对标")
                .taskType("COMPARISON").outputType(ArtifactType.COMPARISON_REPORT).inputBindings(comparisonInputs)
                .resourceRequirement(ResourceRequirement.llm()).failurePolicy(FailurePolicy.CONTINUE).build();
        graph.addNode(comparison);
        analyses.forEach(node -> graph.addEdge(node.getNodeId(), comparison.getNodeId()));
        List<InputBinding> synthesisInputs = new ArrayList<>();
        synthesisInputs.add(new InputBinding("comparison", comparison.getNodeId(), ArtifactType.COMPARISON_REPORT, "$", true));
        if (macro != null) synthesisInputs.add(new InputBinding("macro", macro.getNodeId(), ArtifactType.MACRO_FACTS, "$", true));
        GraphNode synthesis = GraphNode.builder().nodeId("synthesis").name("专业投资研报终审合成")
                .taskType("SYNTHESIS").outputType(ArtifactType.FINAL_REPORT).inputBindings(synthesisInputs)
                .priority(NodePriority.HIGH).resourceRequirement(ResourceRequirement.llm())
                .failurePolicy(FailurePolicy.FAIL_FAST).build();
        graph.addNode(synthesis); graph.addEdge(comparison.getNodeId(), synthesis.getNodeId());
        if (macro != null) graph.addEdge(macro.getNodeId(), synthesis.getNodeId());
        return graph;
    }

    private GraphNode node(String id, String name, String type, ArtifactType output,
                           Map<String, Object> params, ResourceRequirement resource) {
        return GraphNode.builder().nodeId(id).name(name).taskType(type).outputType(output).params(params)
                .resourceRequirement(resource).failurePolicy(FailurePolicy.CONTINUE).build();
    }
}
