package com.financial.copilot.agent.core.dag.planner;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactMetadata;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.artifact.EvidenceContract;
import com.financial.copilot.agent.core.dag.artifact.payload.FundPool;
import com.financial.copilot.agent.core.dag.artifact.payload.FundResearchResult;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.model.patch.GraphOperation;
import com.financial.copilot.agent.core.dag.model.patch.GraphPatch;
import com.financial.copilot.agent.core.dag.model.patch.PatchOp;
import com.financial.copilot.agent.core.dag.planner.tool.CapabilityRegistryTool;
import com.financial.copilot.agent.core.dag.planner.tool.MarketMemoryTool;
import com.financial.copilot.agent.core.dag.planner.tool.MetricRAGTool;
import com.financial.copilot.agent.core.dag.planner.tool.SkillRegistryTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>动态图规划器单元测试 (GraphPlannerTest)</h1>
 *
 * @author FinancialCopilot
 */
class GraphPlannerTest {

    @Test
    @DisplayName("测试根据自然语言指令生成标准四步执行图拓扑")
    void testPlanInitialGraph() {
        GraphPlanner planner = new GraphPlanner();
        ExecutionGraph graph = planner.planInitialGraph("筛选三年稳健医药基金");

        assertNotNull(graph);
        assertThat(graph.getNodes()).hasSize(4);
        assertNotNull(graph.getNode("step-1-screening"));
        assertNotNull(graph.getNode("step-2-analysis"));
        assertNotNull(graph.getNode("step-3-comparison"));
        assertNotNull(graph.getNode("step-4-synthesis"));

        // 验证拓扑依赖链
        assertThat(graph.getDownstream("step-1-screening")).contains("step-2-analysis");
        assertThat(graph.getDownstream("step-2-analysis")).contains("step-3-comparison");
        assertThat(graph.getDownstream("step-3-comparison")).contains("step-4-synthesis");
        assertFalse(graph.hasCycle());
    }

    @Test
    @DisplayName("测试结合会话长期记忆生成带偏好约束的初始执行图")
    void testPlanInitialGraphWithSessionMemory() {
        MarketMemoryTool mockMemoryTool = Mockito.mock(MarketMemoryTool.class);
        Mockito.when(mockMemoryTool.retrieveMemory("session-888", "低风险理财", 5))
                .thenReturn(new MarketMemoryTool.MemoryRetrievalResult(
                        "session-888",
                        List.of("用户偏好回撤<10%", "偏好纯债与固收+"),
                        List.of()
                ));

        GraphPlanner planner = new GraphPlanner(
                new MetricRAGTool(),
                new SkillRegistryTool(),
                new CapabilityRegistryTool(),
                mockMemoryTool
        );

        ExecutionGraph graph = planner.planInitialGraph("低风险理财", "session-888");
        GraphNode screenNode = graph.getNode("step-1-screening");
        assertNotNull(screenNode);
        assertTrue(screenNode.getParams().containsKey("historicalPreferences"));
        @SuppressWarnings("unchecked")
        List<String> prefs = (List<String>) screenNode.getParams().get("historicalPreferences");
        assertThat(prefs).contains("用户偏好回撤<10%");
    }

    @Test
    @DisplayName("测试初筛标的池为空触发生成放宽条件重试节点 Patch")
    void testPlanPatchWhenScreeningPoolEmpty() {
        GraphPlanner planner = new GraphPlanner();
        ExecutionGraph graph = planner.planInitialGraph("严苛条件筛选基金");

        FundPool emptyPool = FundPool.ofCodes(List.of(), "医药初筛命中为0");
        Artifact<FundPool> emptyArtifact = Artifact.of(
                "art-pool-0",
                ArtifactType.FUND_POOL,
                "step-1-screening",
                emptyPool,
                ArtifactMetadata.standard("Screener")
        );

        GraphPatch patch = planner.planPatch(graph, "step-1-screening", emptyArtifact);
        assertNotNull(patch);
        assertThat(patch.operations()).isNotEmpty();
        assertTrue(patch.operations().stream().anyMatch(op -> op.op() == PatchOp.ADD_NODE && "step-1-screening-relaxed".equals(op.nodeId())));
    }

    @Test
    @DisplayName("测试体检候选标的仅剩1只时动态将下游 COMPARISON 节点降级为 DEEP_DIVE")
    void testPlanPatchWhenSingleTopCandidateTriggersDowngrade() {
        GraphPlanner planner = new GraphPlanner();
        ExecutionGraph graph = planner.planInitialGraph("筛选独角兽医药基金");

        FundResearchResult singleCandidateResult = FundResearchResult.ofBatch(
                List.of(Map.of("fundCode", "003095")),
                List.of("003095")
        );
        Artifact<FundResearchResult> researchArtifact = Artifact.of(
                "art-res-1",
                ArtifactType.FUND_RESEARCH,
                "step-2-analysis",
                singleCandidateResult,
                ArtifactMetadata.standard("Analyzer")
        );

        GraphPatch patch = planner.planPatch(graph, "step-2-analysis", researchArtifact);
        assertNotNull(patch, "候选标的仅 1 只时应生成自适应降级 Patch");
        assertThat(patch.operations()).hasSize(1);

        GraphOperation updateOp = patch.operations().get(0);
        assertEquals(PatchOp.UPDATE_NODE, updateOp.op());
        assertEquals("step-3-comparison", updateOp.nodeId());
        assertEquals("DEEP_DIVE", updateOp.params().get("taskType"));
        assertEquals("003095", updateOp.params().get("targetCode"));
        assertEquals(Boolean.TRUE, updateOp.params().get("downgradedToSingle"));

        // 应用 Patch 并验证节点动态生效
        graph.applyPatch(patch);
        GraphNode downstreamNode = graph.getNode("step-3-comparison");
        assertEquals("DEEP_DIVE", downstreamNode.getTaskType());
        assertEquals("单一最优标的穿透式深度剖析", downstreamNode.getName());
    }

    @Test
    @DisplayName("测试证据链缺失时动态插入补数节点 Patch")
    void testPlanPatchWhenMissingEvidence() {
        GraphPlanner planner = new GraphPlanner();
        ExecutionGraph graph = planner.planInitialGraph("分析标的");

        EvidenceContract incompleteContract = EvidenceContract.insufficient(List.of("quarterly_turnover_rate"));
        Artifact<String> artifact = new Artifact<>(
                "art-temp",
                ArtifactType.FUND_RESEARCH,
                "step-2-analysis",
                "体检打分88",
                ArtifactMetadata.standard("Analyzer"),
                incompleteContract
        );

        GraphPatch patch = planner.planPatch(graph, "step-2-analysis", artifact);
        assertNotNull(patch);
        assertTrue(patch.operations().stream().anyMatch(op -> op.op() == PatchOp.ADD_NODE && "supplement-quarterly_turnover_rate".equals(op.nodeId())));
    }
}
