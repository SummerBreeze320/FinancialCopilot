package com.financial.copilot.agent.core.infra.workflow;

import com.financial.copilot.agent.core.business.agents.*;
import com.financial.copilot.agent.core.infra.dag.artifact.*;
import com.financial.copilot.agent.core.infra.dag.guard.*;
import com.financial.copilot.agent.core.infra.dag.model.*;
import com.financial.copilot.agent.core.infra.dag.planner.*;
import com.financial.copilot.agent.core.infra.dag.runtime.*;
import com.financial.copilot.agent.core.infra.dag.runtime.checkpoint.*;
import com.financial.copilot.agent.core.infra.dag.runtime.resource.ResourceManager;
import com.financial.copilot.agent.core.infra.dag.scheduler.GlobalReadyScheduler;
import com.financial.copilot.agent.core.infra.dag.scheduler.RunAdmissionController;
import com.financial.copilot.agent.core.infra.observability.RuntimeMetrics;
import com.financial.copilot.agent.core.infra.event.WorkflowFinishedEvent;
import com.financial.copilot.agent.core.infra.llm.dto.LlmResponse;
import com.financial.copilot.agent.core.infra.memory.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.platform.execution.entity.ResearchRunEvent;
import com.financial.copilot.domain.platform.execution.port.ResearchExecutionPort;
import com.financial.copilot.agent.core.infra.supervisor.SupervisorWorkflow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Sole application entry for planning and executing financial research graphs. */
@Service
public class FinancialResearchWorkflow {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentNodeRouter agentRouter;
    private final GraphPlannerAgent planner;
    private final DagRuntime runtime;
    private final GraphRunRegistry registry;
    private final DagCheckpointStore checkpointStore;
    private final RunAdmissionController admissionController;
    private final ResearchExecutionPort executionPort;
    private final RuntimeMetrics runtimeMetrics;
    private final SupervisorWorkflow supervisorWorkflow;

    public FinancialResearchWorkflow(
            AgentNodeRouter agentRouter,
            GraphPlannerAgent planner,
            ResourceManager resources,
            DagCheckpointStore checkpointStore,
            NodeQualityGate qualityGate,
            ReplanPolicy replanPolicy,
            GraphRunRegistry registry) {
        this(agentRouter, planner, resources, checkpointStore, qualityGate, replanPolicy, registry, null, null, null, null, null, null);
    }

    public FinancialResearchWorkflow(
            AgentNodeRouter agentRouter,
            GraphPlannerAgent planner,
            ResourceManager resources,
            DagCheckpointStore checkpointStore,
            NodeQualityGate qualityGate,
            ReplanPolicy replanPolicy,
            GraphRunRegistry registry,
            GlobalReadyScheduler globalReadyScheduler,
            RunAdmissionController admissionController,
            ResearchExecutionPort executionPort) {
        this(agentRouter, planner, resources, checkpointStore, qualityGate, replanPolicy, registry, globalReadyScheduler, admissionController, executionPort, null, null, null);
    }

    public FinancialResearchWorkflow(
            AgentNodeRouter agentRouter,
            GraphPlannerAgent planner,
            ResourceManager resources,
            DagCheckpointStore checkpointStore,
            NodeQualityGate qualityGate,
            ReplanPolicy replanPolicy,
            GraphRunRegistry registry,
            GlobalReadyScheduler globalReadyScheduler,
            RunAdmissionController admissionController,
            ResearchExecutionPort executionPort,
            RuntimeMetrics runtimeMetrics) {
        this(agentRouter, planner, resources, checkpointStore, qualityGate, replanPolicy, registry, globalReadyScheduler, admissionController, executionPort, runtimeMetrics, null, null);
    }

    public FinancialResearchWorkflow(
            AgentNodeRouter agentRouter,
            GraphPlannerAgent planner,
            ResourceManager resources,
            DagCheckpointStore checkpointStore,
            NodeQualityGate qualityGate,
            ReplanPolicy replanPolicy,
            GraphRunRegistry registry,
            GlobalReadyScheduler globalReadyScheduler,
            RunAdmissionController admissionController,
            ResearchExecutionPort executionPort,
            RuntimeMetrics runtimeMetrics,
            SupervisorWorkflow supervisorWorkflow) {
        this(agentRouter, planner, resources, checkpointStore, qualityGate, replanPolicy, registry, globalReadyScheduler, admissionController, executionPort, runtimeMetrics, null, supervisorWorkflow);
    }

    @Autowired
    public FinancialResearchWorkflow(
            AgentNodeRouter agentRouter,
            GraphPlannerAgent planner,
            @Autowired(required = false) ResourceManager resources,
            @Autowired(required = false) DagCheckpointStore checkpointStore,
            @Autowired(required = false) NodeQualityGate qualityGate,
            @Autowired(required = false) ReplanPolicy replanPolicy,
            @Autowired(required = false) GraphRunRegistry registry,
            @Autowired(required = false) GlobalReadyScheduler globalReadyScheduler,
            @Autowired(required = false) RunAdmissionController admissionController,
            @Autowired(required = false) ResearchExecutionPort executionPort,
            @Autowired(required = false) RuntimeMetrics runtimeMetrics,
            @Autowired(required = false) DagRuntime dagRuntime,
            @Autowired(required = false) SupervisorWorkflow supervisorWorkflow) {
        this.agentRouter = agentRouter;
        this.planner = planner;
        this.executionPort = executionPort;
        this.registry = registry != null ? registry
                : (executionPort == null ? new GraphRunRegistry() : new GraphRunRegistry(executionPort));
        this.checkpointStore = checkpointStore == null ? new InMemoryDagCheckpointStore() : checkpointStore;
        this.admissionController = admissionController;
        this.runtimeMetrics = runtimeMetrics;
        if (dagRuntime != null) {
            this.runtime = dagRuntime;
        } else {
            NodeExecutor dispatcher = this::dispatch;
            this.runtime = new DagRuntime(dispatcher,
                    resources == null ? ResourceManager.defaultManager() : resources,
                    this.checkpointStore,
                    qualityGate == null ? new DefaultNodeQualityGate() : qualityGate,
                    replanPolicy == null ? ReplanPolicy.heuristic() : replanPolicy,
                    planner,
                    globalReadyScheduler,
                    admissionController,
                    executionPort);
        }
        this.supervisorWorkflow = supervisorWorkflow;
    }

    public RunAdmissionController getAdmissionController() {
        return admissionController;
    }

    public SupervisorWorkflow getSupervisorWorkflow() {
        return supervisorWorkflow;
    }

    public GraphRunHandle run(GraphRunRequest request) {
        if (admissionController != null) {
            RunAdmissionController.AdmissionTicket ticket = admissionController.requestAdmission(
                    request.tenantId(), request.userId(), request.conversationId(), request.runId());
            if (ticket.decision() == com.financial.copilot.agent.core.infra.dag.scheduler.AdmissionDecision.REJECTED_SYSTEM_CAPACITY) {
                throw new com.financial.copilot.common.exception.SystemCapacityExceededException("系统排队容量已达上限，请稍后重试");
            }
            if (ticket.decision() == com.financial.copilot.agent.core.infra.dag.scheduler.AdmissionDecision.REJECTED_USER_LIMIT) {
                throw new com.financial.copilot.common.exception.UserLimitExceededException(request.userId(), "用户并发运行或排队已达上限，请稍后重试");
            }
            if (ticket.decision() == com.financial.copilot.agent.core.infra.dag.scheduler.AdmissionDecision.ADMITTED) {
                GraphRunRequest effectiveRequest = request.resolveSnapshot();
                ExecutionGraph graph = planner.plan(new GraphPlanningRequest(effectiveRequest.prompt(), effectiveRequest.sessionKey(),
                        effectiveRequest.profile(), effectiveRequest.usageConsumer(), effectiveRequest.enableThinking(), effectiveRequest));
                GraphRunHandle innerHandle = supervisorWorkflow != null
                        ? supervisorWorkflow.runHandle(effectiveRequest, graph)
                        : runtime.run(effectiveRequest, graph);
                GraphRunHandle handle = registry.register(effectiveRequest.userId(), innerHandle);
                return handle;
            }
            return handleQueuedRun(request, ticket);
        }

        GraphRunRequest effectiveRequest = request.resolveSnapshot();
        ExecutionGraph graph = planner.plan(new GraphPlanningRequest(effectiveRequest.prompt(), effectiveRequest.sessionKey(),
                effectiveRequest.profile(), effectiveRequest.usageConsumer(), effectiveRequest.enableThinking(), effectiveRequest));
        GraphRunHandle innerHandle = supervisorWorkflow != null
                ? supervisorWorkflow.runHandle(effectiveRequest, graph)
                : runtime.run(effectiveRequest, graph);
        GraphRunHandle handle = registry.register(effectiveRequest.userId(), innerHandle);
        return handle;
    }

    private GraphRunHandle handleQueuedRun(GraphRunRequest request, RunAdmissionController.AdmissionTicket ticket) {
        com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken cancellationToken =
                new com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken(request.runId());
        com.financial.copilot.agent.core.infra.dag.event.NodeEventBus eventBus =
                new com.financial.copilot.agent.core.infra.dag.event.NodeEventBus(cancellationToken, event -> {
                    if (executionPort != null && event != null) {
                        try {
                            UUID runUuid = UUID.fromString(request.runId());
                            String payload = OBJECT_MAPPER.writeValueAsString(event);
                            ResearchRunEvent persisted = executionPort.appendEvent(runUuid, request.userId(), event.getType(), payload, Instant.now());
                            event.setSequenceNo(persisted.sequenceNo());
                        } catch (Exception ex) {}
                    }
                });

        // 1. 发射排队初始事件 (含排队位次)
        eventBus.publishRunQueued(request.runId(), request.conversationId().toString(),
                ticket.decision().name(), ticket.queuePosition());

        CompletableFuture<GraphRunResult> completion = new CompletableFuture<>();
        cancellationToken.onCancel(() -> {
            if (admissionController != null) {
                admissionController.cancelQueuedRun(request.runId());
            }
            completion.cancel(true);
        });

        // 2. 挂接异步准入监听
        ticket.onAdmittedFuture().whenComplete((decision, error) -> {
            if (error != null) {
                eventBus.emit(com.financial.copilot.common.event.ResearchStreamEvent.runFailed(request.runId(), error.getMessage()));
                eventBus.complete();
                completion.completeExceptionally(error);
                return;
            }
            if (cancellationToken.isCancelled()) {
                if (admissionController != null) {
                    admissionController.onRunCompleted(request.runId());
                }
                eventBus.emit(com.financial.copilot.common.event.ResearchStreamEvent.runCancelled(request.runId(), "Cancelled before execution"));
                eventBus.complete();
                completion.cancel(true);
                return;
            }

            // 准入通知
            eventBus.publishRunAdmitted(request.runId(), request.conversationId().toString());
            eventBus.publishRunStarted(request.runId(), request.conversationId().toString());

            // 捕获最新上下文快照并规划图
            GraphRunRequest activeRequest = request.resolveSnapshot();
            try {
                ExecutionGraph graph = planner.plan(new GraphPlanningRequest(
                        activeRequest.prompt(), activeRequest.sessionKey(),
                        activeRequest.profile(), activeRequest.usageConsumer(),
                        activeRequest.enableThinking(), activeRequest));

                GraphRunHandle innerHandle = supervisorWorkflow != null
                        ? supervisorWorkflow.runHandle(activeRequest, graph)
                        : runtime.run(activeRequest, graph);
                innerHandle.events().subscribe(
                        eventBus::emit,
                        err -> {
                            eventBus.error(err);
                            completion.completeExceptionally(err);
                        },
                        eventBus::complete
                );
                innerHandle.completion().whenComplete((res, err) -> {
                    if (err != null) {
                        completion.completeExceptionally(err);
                    } else {
                        completion.complete(res);
                    }
                });
            } catch (Throwable t) {
                if (admissionController != null) {
                    admissionController.onRunCompleted(activeRequest.runId());
                }
                eventBus.emit(com.financial.copilot.common.event.ResearchStreamEvent.runFailed(activeRequest.runId(), t.getMessage()));
                eventBus.complete();
                completion.completeExceptionally(t);
            }
        });

        GraphRunHandle handle = new GraphRunHandle(request.runId(), eventBus.flux(), completion, cancellationToken::cancel);
        registry.register(request.userId(), handle);
        return handle;
    }

    public Optional<GraphRunHandle> findRun(Long userId, String runId) {
        return registry.find(userId, runId);
    }

    public Optional<DagCheckpoint> findCheckpoint(Long userId, String runId) {
        return checkpointStore.load(userId, runId);
    }

    public boolean cancel(Long userId, String runId, String reason) {
        if (admissionController != null) {
            admissionController.cancelQueuedRun(runId);
        }
        return registry.cancel(userId, runId, reason);
    }

    public GraphRunHandle resume(Long userId, String runId, Consumer<LlmResponse> usageConsumer) {
        return registry.register(userId, runtime.resume(userId, runId, usageConsumer));
    }

    private Artifact<?> dispatch(GraphNode node, NodeInput input, NodeExecutionContext context) {
        Instant start = Instant.now();
        try {
            return agentRouter.execute(node, input, context);
        } finally {
            if (runtimeMetrics != null && node != null) {
                runtimeMetrics.recordNodeDuration(node.getTaskType(), Duration.between(start, Instant.now()));
            }
        }
    }

    @PreDestroy
    void closeRuntime() {
        runtime.close();
    }

}
