package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactMetadata;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.artifact.EvidenceContract;
import com.financial.copilot.agent.core.dag.guard.DefaultNodeQualityGate;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.FailurePolicy;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.model.patch.GraphOperation;
import com.financial.copilot.agent.core.dag.model.patch.GraphPatch;
import com.financial.copilot.agent.core.dag.artifact.payload.FundResearchResult;
import com.financial.copilot.agent.core.dag.planner.GraphPlanner;
import com.financial.copilot.agent.core.dag.planner.tool.MetricRAGTool;
import com.financial.copilot.agent.core.dag.planner.tool.SkillRegistryTool;
import com.financial.copilot.agent.core.dag.runtime.checkpoint.InMemoryDagCheckpointStore;
import com.financial.copilot.agent.core.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.dag.runtime.resource.NodePriority;
import com.financial.copilot.agent.core.dag.runtime.resource.ResourceManager;
import com.financial.copilot.agent.core.dag.runtime.resource.ResourceRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 单元测试：ReplanPolicy 与动态 GraphPatch 自适应改图
 */
class DynamicReplanTest {

    @Test
    @DisplayName("测试正常产物零开销快速路径（不触发 RePlanAdvisor，版本号不变更）")
    void testNormalNodePassesWithoutReplan() throws Exception {
        ExecutionGraph graph = new ExecutionGraph("static-graph");
        GraphNode n1 = GraphNode.builder()
                .nodeId("N1")
                .name("SCREENING")
                .failurePolicy(FailurePolicy.FAIL_FAST)
                .build();
        graph.addNode(n1);

        ArtifactStore store = new ArtifactStore();
        AtomicInteger advisorInvocations = new AtomicInteger(0);

        RePlanAdvisor mockAdvisor = (g, nodeId, art) -> {
            advisorInvocations.incrementAndGet();
            return null;
        };

        DagRuntime runtime = new DagRuntime(
                (node, s, t) -> Artifact.of("art_N1", ArtifactType.FUND_POOL, "N1", List.of("003095"), ArtifactMetadata.standard("TEST")),
                store,
                ResourceManager.defaultManager(),
                new InMemoryDagCheckpointStore(),
                new DefaultNodeQualityGate(),
                ReplanPolicy.heuristic(),
                mockAdvisor
        );

        runtime.executeGraph(graph, new CancellationToken("run-1"), e -> {}).get(2, TimeUnit.SECONDS);

        assertEquals(0, advisorInvocations.get(), "正常数据不应调用 RePlanAdvisor");
        assertEquals(0, graph.getRevision(), "图版本号应维持 0");
        assertNotNull(store.get("N1"));
    }

    @Test
    @DisplayName("测试初筛空池触发 RePlanAdvisor 生成 GraphPatch 并动态补数执行")
    void testEmptyCandidatePoolTriggersReplanAndPatchesGraph() throws Exception {
        ExecutionGraph graph = new ExecutionGraph("adaptive-graph");
        GraphNode screenNode = GraphNode.builder()
                .nodeId("screen")
                .name("SCREENING")
                .failurePolicy(FailurePolicy.FAIL_FAST)
                .build();
        GraphNode reportNode = GraphNode.builder()
                .nodeId("report")
                .name("SYNTHESIS")
                .failurePolicy(FailurePolicy.FAIL_FAST)
                .build();
        graph.addNode(screenNode);
        graph.addNode(reportNode);
        graph.addEdge("screen", "report");

        ArtifactStore store = new ArtifactStore();
        List<String> executedNodes = new CopyOnWriteArrayList<>();

        // RePlanAdvisor: 发现初筛为空时，生成 GraphPatch 动态插入宽松筛选节点
        RePlanAdvisor advisor = (g, nodeId, art) -> {
            if ("screen".equals(nodeId)) {
                GraphNode relaxedNode = GraphNode.builder()
                        .nodeId("screen_relaxed")
                        .name("RELAXED_SCREENING")
                        .failurePolicy(FailurePolicy.FAIL_FAST)
                        .build();

                return new GraphPatch(
                        g.getRevision(),
                        List.of(
                                GraphOperation.addNode(relaxedNode),
                                GraphOperation.addEdge("screen_relaxed", "report")
                        ),
                        "初筛为空，动态插入放宽条件筛选节点"
                );
            }
            return null;
        };

        NodeExecutor executor = (node, s, t) -> {
            executedNodes.add(node.getNodeId());
            if ("screen".equals(node.getNodeId())) {
                // 初筛命中 0 只标的
                return Artifact.of("art_screen", ArtifactType.FUND_POOL, "screen", List.of(), ArtifactMetadata.standard("TEST"));
            } else if ("screen_relaxed".equals(node.getNodeId())) {
                // 宽松筛选命中 3 只标的
                return Artifact.of("art_relaxed", ArtifactType.FUND_POOL, "screen_relaxed", List.of("005827", "161005", "003095"), ArtifactMetadata.standard("RELAXED"));
            } else if ("report".equals(node.getNodeId())) {
                return Artifact.of("art_report", ArtifactType.FINAL_REPORT, "report", "最终研报内容", ArtifactMetadata.standard("SYNTHESIS"));
            }
            throw new IllegalArgumentException("Unknown node: " + node.getNodeId());
        };

        DagRuntime runtime = new DagRuntime(
                executor,
                store,
                ResourceManager.defaultManager(),
                new InMemoryDagCheckpointStore(),
                new DefaultNodeQualityGate(),
                ReplanPolicy.heuristic(),
                advisor
        );

        runtime.executeGraph(graph, new CancellationToken("run-adaptive"), e -> {}).get(3, TimeUnit.SECONDS);

        // 验证执行链路：初筛 -> 动态插入宽松筛选 -> 最终研报
        assertThat(executedNodes).contains("screen", "screen_relaxed", "report");
        assertEquals(1, graph.getRevision(), "图成功应用 Patch，版本号递增至 1");
        assertNotNull(store.get("screen_relaxed"));
        assertNotNull(store.get("report"));
    }

    @Test
    @DisplayName("测试证据契约不足触发补数节点")
    void testMissingEvidenceContractTriggersSupplementNode() throws Exception {
        ExecutionGraph graph = new ExecutionGraph("evidence-graph");
        GraphNode analysisNode = GraphNode.builder()
                .nodeId("analysis")
                .name("FUND_ANALYSIS")
                .failurePolicy(FailurePolicy.FAIL_FAST)
                .build();
        graph.addNode(analysisNode);

        ArtifactStore store = new ArtifactStore();
        List<String> executedNodes = new CopyOnWriteArrayList<>();

        RePlanAdvisor advisor = (g, nodeId, art) -> {
            if ("analysis".equals(nodeId) && art.evidenceContract() != null && !art.evidenceContract().isSufficient()) {
                GraphNode supplementNode = GraphNode.builder()
                        .nodeId("fetch_turnover")
                        .name("FETCH_TURNOVER")
                        .failurePolicy(FailurePolicy.FAIL_FAST)
                        .build();

                return new GraphPatch(
                        g.getRevision(),
                        List.of(GraphOperation.addNode(supplementNode)),
                        "缺失换手率数据，动态补数"
                );
            }
            return null;
        };

        NodeExecutor executor = (node, s, t) -> {
            executedNodes.add(node.getNodeId());
            if ("analysis".equals(node.getNodeId())) {
                EvidenceContract contract = EvidenceContract.insufficient(List.of("turnover_rate"));
                return new Artifact<>("art_analysis", ArtifactType.FUND_RESEARCH, "analysis", Map.of("score", 85), ArtifactMetadata.standard("TEST"), contract);
            } else if ("fetch_turnover".equals(node.getNodeId())) {
                return Artifact.of("art_turnover", ArtifactType.GENERAL, "fetch_turnover", Map.of("turnover", "120%"), ArtifactMetadata.standard("DPU"));
            }
            throw new IllegalArgumentException("Unknown: " + node.getNodeId());
        };

        DagRuntime runtime = new DagRuntime(
                executor,
                store,
                ResourceManager.defaultManager(),
                new InMemoryDagCheckpointStore(),
                new DefaultNodeQualityGate(),
                ReplanPolicy.heuristic(),
                advisor
        );

        runtime.executeGraph(graph, new CancellationToken("run-evidence"), e -> {}).get(3, TimeUnit.SECONDS);

        assertThat(executedNodes).contains("analysis", "fetch_turnover");
        assertEquals(1, graph.getRevision());
        assertNotNull(store.get("fetch_turnover"));
    }

    @Test
    @DisplayName("测试 MetricRAGTool 与 SkillRegistryTool 工具调用能力")
    void testPlannerTools() {
        MetricRAGTool metricTool = new MetricRAGTool();
        MetricRAGTool.MetricSearchResult steadyResult = metricTool.searchMetrics("三年稳健低回撤");
        assertThat(steadyResult.matchedMetrics()).contains("max_drawdown", "calmar_ratio");

        MetricRAGTool.MetricSearchResult highReturnResult = metricTool.searchMetrics("超额收益进攻");
        assertThat(highReturnResult.matchedMetrics()).contains("annualized_return", "alpha");

        SkillRegistryTool skillTool = new SkillRegistryTool();
        List<SkillRegistryTool.SkillDescriptor> skills = skillTool.listSkills();
        assertThat(skills).isNotEmpty();
        assertTrue(skills.stream().anyMatch(s -> "fund-screener".equals(s.skillName())));
        assertTrue(skills.stream().anyMatch(s -> "report-synthesizer".equals(s.skillName())));
    }

    @Test
    @DisplayName("测试体检仅剩单标的时自适应触发动态降级补丁并无缝执行单标的深度剖析")
    void testSingleCandidateDowngradesComparisonNodeToDeepDive() throws Exception {
        ExecutionGraph graph = new ExecutionGraph("downgrade-graph");
        GraphNode analysisNode = GraphNode.builder()
                .nodeId("step-2-analysis")
                .name("候选标的量化体检")
                .taskType("BATCH_ANALYSIS")
                .failurePolicy(FailurePolicy.FAIL_FAST)
                .build();
        GraphNode compNode = GraphNode.builder()
                .nodeId("step-3-comparison")
                .name("决赛圈标的深度横向对标")
                .taskType("COMPARISON")
                .failurePolicy(FailurePolicy.FAIL_FAST)
                .build();
        graph.addNode(analysisNode);
        graph.addNode(compNode);
        graph.addEdge("step-2-analysis", "step-3-comparison");

        ArtifactStore store = new ArtifactStore();
        List<String> executedNodeTypes = new CopyOnWriteArrayList<>();
        GraphPlanner planner = new GraphPlanner();

        NodeExecutor executor = (node, s, t) -> {
            executedNodeTypes.add(node.getTaskType());
            if ("step-2-analysis".equals(node.getNodeId())) {
                FundResearchResult singleResult = FundResearchResult.ofBatch(
                        List.of(Map.of("fundCode", "003095")),
                        List.of("003095")
                );
                return Artifact.of("art-analysis", ArtifactType.FUND_RESEARCH, node.getNodeId(), singleResult, ArtifactMetadata.standard("TEST"));
            } else if ("step-3-comparison".equals(node.getNodeId())) {
                // 验证节点在执行时已动态生效为 DEEP_DIVE
                assertEquals("DEEP_DIVE", node.getTaskType());
                assertEquals("003095", node.getParams().get("targetCode"));
                return Artifact.of("art-deepdive", ArtifactType.COMPARISON_REPORT, node.getNodeId(), "003095深度剖析", ArtifactMetadata.standard("TEST"));
            }
            throw new IllegalArgumentException("Unknown: " + node.getNodeId());
        };

        DagRuntime runtime = new DagRuntime(
                executor,
                store,
                ResourceManager.defaultManager(),
                new InMemoryDagCheckpointStore(),
                new DefaultNodeQualityGate(),
                ReplanPolicy.heuristic(),
                planner
        );

        runtime.executeGraph(graph, new CancellationToken("run-downgrade"), e -> {}).get(3, TimeUnit.SECONDS);

        assertEquals(1, graph.getRevision(), "图版本号应因自适应降级递增为 1");
        assertThat(executedNodeTypes).containsExactly("BATCH_ANALYSIS", "DEEP_DIVE");
        assertEquals("DEEP_DIVE", graph.getNode("step-3-comparison").getTaskType());
    }
}
