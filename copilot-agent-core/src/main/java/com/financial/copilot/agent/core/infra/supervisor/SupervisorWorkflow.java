package com.financial.copilot.agent.core.infra.supervisor;

import com.financial.copilot.agent.core.business.agents.AggregatorAgent;
import com.financial.copilot.agent.core.business.agents.ReviewerAgent;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.model.NodeStatus;
import com.financial.copilot.agent.core.infra.dag.planner.GraphPlannerAgent;
import com.financial.copilot.agent.core.infra.dag.runtime.DagRuntime;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunHandle;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunResult;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.infra.dag.validator.ArtifactValidator;
import com.financial.copilot.agent.core.infra.dag.validator.PlanValidationResult;
import com.financial.copilot.agent.core.infra.dag.validator.PlanValidator;
import com.financial.copilot.common.event.ResearchStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * <h1>Supervisor 主管工作流闭环控制器 (SupervisorWorkflow)</h1>
 * <p>
 * 将投研执行链路重构为显式的:
 * {@code Supervisor → Planner → Scheduler → Subagents → Validator → Reviewer → Aggregator} 闭环。
 * 严格遵从状态机迁移约束与有界预算，防止死循环；支持针对性重试、局部重规划与降级披露。
 * </p>
 */
@Component
public class SupervisorWorkflow {

    private static final Logger log = LoggerFactory.getLogger(SupervisorWorkflow.class);

    private final PlanValidator planValidator;
    private final DagRuntime runtime;
    private final ArtifactValidator artifactValidator;
    private final ReviewerAgent reviewer;
    private final AggregatorAgent aggregator;
    private final SupervisorBudget budget;
    private final GraphPlannerAgent planner;

    @Autowired
    public SupervisorWorkflow(
            PlanValidator planValidator,
            DagRuntime runtime,
            ArtifactValidator artifactValidator,
            ReviewerAgent reviewer,
            AggregatorAgent aggregator,
            @Autowired(required = false) SupervisorBudget budget
    ) {
        this(planValidator, runtime, artifactValidator, reviewer, aggregator, budget, null);
    }

    public SupervisorWorkflow(
            PlanValidator planValidator,
            DagRuntime runtime,
            ArtifactValidator artifactValidator,
            ReviewerAgent reviewer,
            AggregatorAgent aggregator,
            SupervisorBudget budget,
            GraphPlannerAgent planner
    ) {
        this.planValidator = Objects.requireNonNull(planValidator, "planValidator cannot be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime cannot be null");
        this.artifactValidator = Objects.requireNonNull(artifactValidator, "artifactValidator cannot be null");
        this.reviewer = Objects.requireNonNull(reviewer, "reviewer cannot be null");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator cannot be null");
        this.budget = budget != null ? budget : SupervisorBudget.defaults();
        this.planner = planner;
    }

    /**
     * 执行投研工作流，返回最终执行结果 Future
     *
     * @param request 执行请求
     * @param graph   执行图
     * @return 最终 GraphRunResult Future
     */
    public CompletableFuture<GraphRunResult> run(GraphRunRequest request, ExecutionGraph graph) {
        return runHandle(request, graph).completion();
    }

    /**
     * 执行投研工作流，返回具备响应式事件流的句柄 (GraphRunHandle)
     *
     * @param request 执行请求
     * @param graph   执行图
     * @return GraphRunHandle
     */
    public GraphRunHandle runHandle(GraphRunRequest request, ExecutionGraph graph) {
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(graph, "graph cannot be null");

        // 1. PLANNING 校验图合法性
        PlanValidationResult planResult = planValidator.validate(graph);
        if (!planResult.isValid()) {
            String errorMsg = "ExecutionGraph validation failed: " + planResult.getErrors();
            log.error("Plan validation failed for run [{}]: {}", request.runId(), errorMsg);
            CompletableFuture<GraphRunResult> failed = CompletableFuture.failedFuture(new IllegalArgumentException(errorMsg));
            return new GraphRunHandle(request.runId(), Flux.just(ResearchStreamEvent.runFailed(request.runId(), errorMsg)),
                    failed, (String ignored) -> {});
        }

        SupervisorStateMachine stateMachine = new SupervisorStateMachine(budget);
        CancellationToken cancellationToken = new CancellationToken(request.runId());
        Sinks.Many<ResearchStreamEvent> eventSink = Sinks.many().replay().limit(256);
        CompletableFuture<GraphRunResult> completion = new CompletableFuture<>();

        cancellationToken.onCancel(() -> {
            String reason = cancellationToken.getReason() != null ? cancellationToken.getReason() : "Cancelled";
            stateMachine.markFailed("Cancelled: " + reason);
            eventSink.tryEmitNext(ResearchStreamEvent.runCancelled(request.runId(), reason));
            eventSink.tryEmitComplete();
            completion.completeExceptionally(new CancellationException(reason));
        });

        // 异步执行 Supervisor 闭环
        Thread.ofVirtual().name("supervisor-" + request.runId()).start(() -> {
            Instant startedAt = Instant.now();
            try {
                executeSupervisorLoop(request, graph, stateMachine, cancellationToken, eventSink, completion, startedAt);
            } catch (Throwable t) {
                log.error("SupervisorWorkflow execution failed for run [{}]: {}", request.runId(), t.getMessage(), t);
                stateMachine.markFailed(t.getMessage());
                eventSink.tryEmitNext(ResearchStreamEvent.runFailed(request.runId(), t.getMessage()));
                eventSink.tryEmitComplete();
                completion.completeExceptionally(t);
            }
        });

        return new GraphRunHandle(request.runId(), eventSink.asFlux(), completion, cancellationToken::cancel);
    }

    private void executeSupervisorLoop(
            GraphRunRequest request,
            ExecutionGraph graph,
            SupervisorStateMachine stateMachine,
            CancellationToken cancellationToken,
            Sinks.Many<ResearchStreamEvent> eventSink,
            CompletableFuture<GraphRunResult> completion,
            Instant startedAt
    ) throws Exception {
        Map<String, Artifact<?>> cumulativeArtifacts = new HashMap<>();
        Map<String, NodeStatus> cumulativeStatuses = new HashMap<>();
        ReviewDecision lastReviewDecision = null;

        while (!stateMachine.isTerminal()) {
            if (cancellationToken.isCancelled()) {
                return;
            }

            SupervisorState current = stateMachine.getCurrentState();
            switch (current) {
                case PLANNING -> {
                    stateMachine.transitionTo(SupervisorState.EXECUTING);
                }
                case EXECUTING -> {
                    // 执行 DAG 图或补丁
                    GraphRunHandle subHandle = runtime.run(request, graph);
                    cancellationToken.onCancel(() -> subHandle.cancel(cancellationToken.getReason()));

                    // 转发 DAG 内部事件（过滤 run_completed，防止提前终结 SSE）
                    subHandle.events()
                            .filter(e -> !"run_completed".equalsIgnoreCase(e.getType()))
                            .subscribe(
                                    eventSink::tryEmitNext,
                                    err -> log.warn("Error streaming subagent events for run [{}]: {}", request.runId(), err.getMessage())
                            );

                    GraphRunResult dagResult = subHandle.completion().join();
                    cumulativeArtifacts.putAll(dagResult.artifacts());
                    cumulativeStatuses.putAll(dagResult.nodeStatuses());

                    stateMachine.transitionTo(SupervisorState.VALIDATING);
                }
                case VALIDATING -> {
                    // 确定性产物验证
                    for (Map.Entry<String, Artifact<?>> entry : cumulativeArtifacts.entrySet()) {
                        GraphNode node = graph.getNode(entry.getKey());
                        if (node != null) {
                            var validation = artifactValidator.validate(node, entry.getValue(), graph);
                            if (!validation.isValid()) {
                                log.warn("Artifact validation error for node [{}]: {}", node.getNodeId(), validation.getErrors());
                            }
                        }
                    }
                    stateMachine.transitionTo(SupervisorState.REVIEWING);
                }
                case REVIEWING -> {
                    int cycle = stateMachine.getReviewCycle() + 1;
                    eventSink.tryEmitNext(ResearchStreamEvent.reviewStarted(request.runId(), cycle, budget.maxReviewCycles()));

                    ArtifactStore store = new ArtifactStore();
                    cumulativeArtifacts.forEach(store::store);

                    NodeExecutionContext reviewContext = new NodeExecutionContext(request, "__reviewer__", store, cancellationToken);
                    ReviewDecision decision = reviewer.review(graph, store, reviewContext);
                    lastReviewDecision = decision;

                    eventSink.tryEmitNext(ResearchStreamEvent.reviewDecision(
                            request.runId(), cycle, decision.action().name(),
                            decision.targetTaskIds(), decision.issues(), decision.confidence()
                    ));

                    stateMachine.evaluateReview(decision);

                    if (stateMachine.getCurrentState() == SupervisorState.EXECUTING) {
                        if (decision.action() == ReviewAction.REPLAN) {
                            eventSink.tryEmitNext(ResearchStreamEvent.replanTriggered(
                                    request.runId(), cycle, String.join("; ", decision.issues())
                            ));
                        }
                    } else if (stateMachine.isDegraded()) {
                        eventSink.tryEmitNext(ResearchStreamEvent.runDegraded(
                            request.runId(), "Reviewer degraded: " + decision.issues(), decision.issues()
                        ));
                    }
                }
                case AGGREGATING -> {
                    // 聚合最终研报
                    ArtifactStore store = new ArtifactStore();
                    cumulativeArtifacts.forEach(store::store);

                    GraphNode aggNode = GraphNode.builder()
                            .nodeId("aggregator")
                            .name("聚合研报")
                            .taskType("SYNTHESIS")
                            .build();

                    NodeInput input = new NodeInput(Map.of(), cumulativeArtifacts);
                    NodeExecutionContext aggContext = new NodeExecutionContext(
                            request, aggNode.getNodeId(), store, cancellationToken, 1, input
                    );

                    Artifact<FinalSynthesisReport> finalReportArt = aggregator.executeWithReview(
                            aggNode, input, aggContext, lastReviewDecision
                    );

                    cumulativeArtifacts.put(finalReportArt.id(), finalReportArt);
                    cumulativeStatuses.put(aggNode.getNodeId(), NodeStatus.SUCCEEDED);

                    stateMachine.markSucceeded();
                }
                default -> {}
            }
        }

        if (stateMachine.getCurrentState() == SupervisorState.SUCCEEDED || stateMachine.getCurrentState() == SupervisorState.DEGRADED) {
            Instant completedAt = Instant.now();
            long durationMs = Duration.between(startedAt, completedAt).toMillis();

            eventSink.tryEmitNext(ResearchStreamEvent.runCompleted(
                    request.runId(), stateMachine.getCurrentState().name(), durationMs
            ));
            eventSink.tryEmitComplete();

            GraphRunResult finalResult = new GraphRunResult(
                    request.runId(),
                    graph,
                    cumulativeStatuses,
                    cumulativeArtifacts,
                    startedAt,
                    completedAt
            );
            completion.complete(finalResult);
        } else if (stateMachine.getCurrentState() == SupervisorState.FAILED) {
            String reason = String.join("; ", stateMachine.getDegradationReasons());
            eventSink.tryEmitNext(ResearchStreamEvent.runFailed(request.runId(), reason));
            eventSink.tryEmitComplete();
            completion.completeExceptionally(new IllegalStateException("Workflow failed: " + reason));
        }
    }
}
