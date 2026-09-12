package com.financial.copilot.agent.core.dag.adapter;

import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.resource.NodePriority;
import com.financial.copilot.agent.core.pipeline.ExecutionPlan;
import com.financial.copilot.agent.core.pipeline.ResearchBlackboard;
import com.financial.copilot.agent.core.pipeline.SubTask;
import com.financial.copilot.common.enums.AssetCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单元测试：LegacyPlanAdapter
 */
class LegacyPlanAdapterTest {

    @Test
    @DisplayName("测试将标准4步ExecutionPlan转换为ExecutionGraph")
    void testConvertExecutionPlanToGraph() {
        List<SubTask> steps = List.of(
                SubTask.builder()
                        .stepIndex(1)
                        .taskType("SCREENING")
                        .description("筛选优质基金标的")
                        .dependsOn(List.of())
                        .outputKey(ResearchBlackboard.KEY_CANDIDATE_FUNDS)
                        .params(Map.of("sector", "医药"))
                        .build(),
                SubTask.builder()
                        .stepIndex(2)
                        .taskType("BATCH_ANALYSIS")
                        .description("评估前5名基金经理")
                        .dependsOn(List.of(1))
                        .inputKey(ResearchBlackboard.KEY_CANDIDATE_FUNDS)
                        .outputKey(ResearchBlackboard.KEY_TOP_CANDIDATES)
                        .params(Map.of("topN", 5))
                        .build(),
                SubTask.builder()
                        .stepIndex(3)
                        .taskType("COMPARISON")
                        .description("横向对标Top2基金")
                        .dependsOn(List.of(2))
                        .inputKey(ResearchBlackboard.KEY_TOP_CANDIDATES)
                        .outputKey(ResearchBlackboard.KEY_COMPARISON_FACTS)
                        .build(),
                SubTask.builder()
                        .stepIndex(4)
                        .taskType("SYNTHESIS")
                        .description("生成最终投研报告")
                        .dependsOn(List.of(3))
                        .inputKey(ResearchBlackboard.KEY_COMPARISON_FACTS)
                        .outputKey(ResearchBlackboard.KEY_FINAL_REPORT)
                        .build()
        );

        ExecutionPlan plan = ExecutionPlan.builder()
                .assetCategory(AssetCategory.FUND)
                .isComplex(true)
                .summary("投研四步流水线")
                .steps(steps)
                .build();

        ExecutionGraph graph = LegacyPlanAdapter.toExecutionGraph(plan);

        assertNotNull(graph);
        assertEquals(4, graph.getNodes().size());

        GraphNode node1 = graph.getNode("step-1");
        GraphNode node2 = graph.getNode("step-2");
        GraphNode node3 = graph.getNode("step-3");
        GraphNode node4 = graph.getNode("step-4");

        assertNotNull(node1);
        assertNotNull(node2);
        assertNotNull(node3);
        assertNotNull(node4);

        assertTrue(graph.getUpstream("step-1").isEmpty(), "step-1 应该是根节点");
        assertTrue(graph.getUpstream("step-2").contains("step-1"), "step-2 应依赖 step-1");
        assertTrue(graph.getUpstream("step-3").contains("step-2"), "step-3 应依赖 step-2");
        assertTrue(graph.getUpstream("step-4").contains("step-3"), "step-4 应依赖 step-3");

        assertEquals(NodePriority.HIGH, node4.getPriority(), "SYNTHESIS 节点应为 HIGH 优先级");

        // 验证元数据保存与提取
        SubTask extractedSubTask = LegacyPlanAdapter.extractSubTask(node1, graph);
        assertNotNull(extractedSubTask);
        assertEquals("SCREENING", extractedSubTask.getTaskType());
        assertEquals("医药", extractedSubTask.getParams().get("sector"));
    }

    @Test
    @DisplayName("测试缺失依赖的线性计划自动串行化兜底")
    void testLinearPlanWithEmptyDependencies() {
        // 如果调用方给出的 steps 依赖全部为空，但有多个步骤，应智能推导为线性链路
        List<SubTask> steps = List.of(
                SubTask.builder().stepIndex(1).taskType("SCREENING").description("步骤1").dependsOn(List.of()).build(),
                SubTask.builder().stepIndex(2).taskType("BATCH_ANALYSIS").description("步骤2").dependsOn(List.of()).build(),
                SubTask.builder().stepIndex(3).taskType("SYNTHESIS").description("步骤3").dependsOn(List.of()).build()
        );

        ExecutionPlan plan = ExecutionPlan.builder()
                .isComplex(true)
                .summary("无显式依赖的旧计划")
                .steps(steps)
                .build();

        ExecutionGraph graph = LegacyPlanAdapter.toExecutionGraph(plan);

        assertTrue(graph.getUpstream("step-2").contains("step-1"), "无显式依赖时应推导 step-2 依赖 step-1");
        assertTrue(graph.getUpstream("step-3").contains("step-2"), "无显式依赖时应推导 step-3 依赖 step-2");
    }
}
