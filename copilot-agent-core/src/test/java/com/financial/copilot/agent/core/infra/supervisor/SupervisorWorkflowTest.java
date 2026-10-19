package com.financial.copilot.agent.core.infra.supervisor;

import com.financial.copilot.agent.core.business.agents.AggregatorAgent;
import com.financial.copilot.agent.core.business.agents.ReviewerAgent;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.model.NodeStatus;
import com.financial.copilot.agent.core.infra.dag.runtime.DagRuntime;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunHandle;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunResult;
import com.financial.copilot.agent.core.infra.dag.runtime.RunMode;
import com.financial.copilot.agent.core.infra.dag.validator.ArtifactValidationResult;
import com.financial.copilot.agent.core.infra.dag.validator.DeterministicArtifactValidator;
import com.financial.copilot.agent.core.infra.dag.validator.PlanValidationResult;
import com.financial.copilot.agent.core.infra.dag.validator.PlanValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("SupervisorWorkflow 闭环编排测试")
class SupervisorWorkflowTest {

    @Test
    @DisplayName("完整闭环：Planner 校验通过 -> 执行 DAG -> Validator 校验 -> Reviewer PASS -> 聚合研报")
    void testEndToEndSupervisorPass() throws Exception {
        PlanValidator planValidator = Mockito.mock(PlanValidator.class);
        when(planValidator.validate(any())).thenReturn(PlanValidationResult.success());

        DagRuntime runtime = Mockito.mock(DagRuntime.class);
        ExecutionGraph graph = new ExecutionGraph("g1");
        GraphNode n1 = GraphNode.builder().nodeId("n1").name("分析").taskType("BATCH_ANALYSIS").build();
        graph.addNode(n1);

        Artifact<String> art = Artifact.of("a1", ArtifactType.FUND_RESEARCH, "n1", "分析事实");
        GraphRunResult initialResult = new GraphRunResult(
                "r1", graph, Map.of("n1", NodeStatus.SUCCEEDED), Map.of("n1", art), Instant.now(), Instant.now()
        );
        GraphRunHandle handle = new GraphRunHandle("r1", Flux.empty(), CompletableFuture.completedFuture(initialResult), ignored -> {});
        when(runtime.run(any(), any())).thenReturn(handle);

        DeterministicArtifactValidator artifactValidator = Mockito.mock(DeterministicArtifactValidator.class);
        when(artifactValidator.validate(any(), any(), any())).thenReturn(ArtifactValidationResult.success());

        ReviewerAgent reviewer = Mockito.mock(ReviewerAgent.class);
        when(reviewer.review(any(), any(), any())).thenReturn(ReviewDecision.pass());

        AggregatorAgent aggregator = Mockito.mock(AggregatorAgent.class);
        FinalSynthesisReport reportPayload = FinalSynthesisReport.of("标题", "摘要", "正文", Map.of(), List.of());
        Artifact<FinalSynthesisReport> reportArt = Artifact.of("rep-1", ArtifactType.FINAL_REPORT, "agg", reportPayload);
        when(aggregator.executeWithReview(any(), any(), any(), any())).thenReturn(reportArt);

        SupervisorWorkflow supervisor = new SupervisorWorkflow(
                planValidator, runtime, artifactValidator, reviewer, aggregator, SupervisorBudget.defaults()
        );

        GraphRunRequest req = new GraphRunRequest("r1", 1L, UUID.randomUUID(), 0L, "sess", "问题", false, null, null, RunMode.SYNC);
        GraphRunResult finalResult = supervisor.run(req, graph).get();

        assertThat(finalResult.runId()).isEqualTo("r1");
        assertThat(finalResult.artifacts()).containsKey("rep-1");
    }

    @Test
    @DisplayName("Reviewer 决策 RETRY 但超出最大重试次数时，Supervisor 自动降级并带有缺口披露完成")
    void testSupervisorDegradeOnExhaustedRetries() throws Exception {
        PlanValidator planValidator = Mockito.mock(PlanValidator.class);
        when(planValidator.validate(any())).thenReturn(PlanValidationResult.success());

        DagRuntime runtime = Mockito.mock(DagRuntime.class);
        ExecutionGraph graph = new ExecutionGraph("g1");
        GraphNode n1 = GraphNode.builder().nodeId("n1").name("分析").taskType("BATCH_ANALYSIS").build();
        graph.addNode(n1);

        Artifact<String> art = Artifact.of("a1", ArtifactType.FUND_RESEARCH, "n1", "基础事实");
        GraphRunResult initialResult = new GraphRunResult(
                "r1", graph, Map.of("n1", NodeStatus.SUCCEEDED), Map.of("n1", art), Instant.now(), Instant.now()
        );
        GraphRunHandle handle = new GraphRunHandle("r1", Flux.empty(), CompletableFuture.completedFuture(initialResult), ignored -> {});
        when(runtime.run(any(), any())).thenReturn(handle);

        DeterministicArtifactValidator artifactValidator = Mockito.mock(DeterministicArtifactValidator.class);
        when(artifactValidator.validate(any(), any(), any())).thenReturn(ArtifactValidationResult.success());

        ReviewerAgent reviewer = Mockito.mock(ReviewerAgent.class);
        // Reviewer 提出需要重试 n1，但 budget 限制 0 次重试
        when(reviewer.review(any(), any(), any())).thenReturn(ReviewDecision.retry(List.of("n1"), "数据不完整"));

        AggregatorAgent aggregator = Mockito.mock(AggregatorAgent.class);
        FinalSynthesisReport reportPayload = FinalSynthesisReport.of("降级标题", "【降级披露】摘要", "正文", Map.of(), List.of());
        Artifact<FinalSynthesisReport> reportArt = Artifact.of("rep-degraded", ArtifactType.FINAL_REPORT, "agg", reportPayload);
        when(aggregator.executeWithReview(any(), any(), any(), any())).thenReturn(reportArt);

        // maxReviewCycles = 0 直接耗尽重试配额
        SupervisorBudget zeroBudget = new SupervisorBudget(0, 0, java.time.Duration.ofMinutes(1), 1000L);
        SupervisorWorkflow supervisor = new SupervisorWorkflow(
                planValidator, runtime, artifactValidator, reviewer, aggregator, zeroBudget
        );

        GraphRunRequest req = new GraphRunRequest("r1", 1L, UUID.randomUUID(), 0L, "sess", "问题", false, null, null, RunMode.SYNC);
        GraphRunResult finalResult = supervisor.run(req, graph).get();

        assertThat(finalResult.runId()).isEqualTo("r1");
        assertThat(finalResult.artifacts()).containsKey("rep-degraded");
    }
}
