package com.financial.copilot.agent.core.workflow;

import com.financial.copilot.agent.core.agents.*;
import com.financial.copilot.agent.core.dag.artifact.*;
import com.financial.copilot.agent.core.dag.guard.*;
import com.financial.copilot.agent.core.dag.model.*;
import com.financial.copilot.agent.core.dag.planner.*;
import com.financial.copilot.agent.core.dag.runtime.*;
import com.financial.copilot.agent.core.dag.runtime.checkpoint.*;
import com.financial.copilot.agent.core.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.dag.runtime.resource.ResourceManager;
import com.financial.copilot.agent.core.event.WorkflowFinishedEvent;
import com.financial.copilot.agent.core.llm.dto.LlmResponse;
import com.financial.copilot.agent.core.memory.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Consumer;

/** Sole application entry for planning and executing financial research graphs. */
@Service
public class FinancialResearchWorkflow {
    private final AgentNodeRouter agentRouter;
    private final ShortTermMemoryService shortMemory;
    private final LongTermMemoryService longMemory;
    private final ApplicationEventPublisher eventPublisher;
    private final GraphPlannerAgent planner;
    private final DagRuntime runtime;
    private final GraphRunRegistry registry;
    private final DagCheckpointStore checkpointStore;

    @Autowired
    public FinancialResearchWorkflow(
            AgentNodeRouter agentRouter,
            @Autowired(required = false) ShortTermMemoryService shortMemory,
            @Autowired(required = false) LongTermMemoryService longMemory,
            @Autowired(required = false) ApplicationEventPublisher eventPublisher,
            GraphPlannerAgent planner,
            @Autowired(required = false) ResourceManager resources,
            @Autowired(required = false) DagCheckpointStore checkpointStore,
            @Autowired(required = false) NodeQualityGate qualityGate,
            @Autowired(required = false) ReplanPolicy replanPolicy,
            @Autowired(required = false) GraphRunRegistry registry) {
        this.agentRouter = agentRouter;
        this.shortMemory = shortMemory;
        this.longMemory = longMemory;
        this.eventPublisher = eventPublisher;
        this.planner = planner;
        this.registry = registry == null ? new GraphRunRegistry() : registry;
        this.checkpointStore = checkpointStore == null ? new RedisDagCheckpointStore() : checkpointStore;
        NodeExecutor dispatcher = new NodeExecutor() {
            @Override
            public Artifact<?> execute(GraphNode node, ArtifactStore store, CancellationToken token) {
                throw new UnsupportedOperationException("Unified runtime requires explicit NodeInput");
            }

            @Override
            public Artifact<?> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
                return dispatch(node, input, context);
            }
        };
        this.runtime = new DagRuntime(dispatcher, new ArtifactStore(),
                resources == null ? ResourceManager.defaultManager() : resources,
                this.checkpointStore,
                qualityGate == null ? new DefaultNodeQualityGate() : qualityGate,
                replanPolicy == null ? ReplanPolicy.heuristic() : replanPolicy,
                planner);
    }

    public GraphRunHandle run(GraphRunRequest request) {
        ExecutionGraph graph = planner.plan(new GraphPlanningRequest(request.prompt(), request.sessionId(),
                request.profile(), request.usageConsumer(), request.enableThinking()));
        GraphRunHandle handle = registry.register(request.userId(), runtime.run(request, graph));
        handle.completion().thenAccept(result -> recordCompletion(request, result));
        return handle;
    }

    public Optional<GraphRunHandle> findRun(Long userId, String runId) {
        return registry.find(userId, runId);
    }

    public Optional<DagCheckpoint> findCheckpoint(Long userId, String runId) {
        return checkpointStore.load(userId, runId);
    }

    public boolean cancel(Long userId, String runId, String reason) {
        return registry.cancel(userId, runId, reason);
    }

    public GraphRunHandle resume(Long userId, String runId, Consumer<LlmResponse> usageConsumer) {
        return registry.register(userId, runtime.resume(userId, runId, usageConsumer));
    }

    private Artifact<?> dispatch(GraphNode node, NodeInput input, NodeExecutionContext context) {
        return agentRouter.execute(node, input, context);
    }

    private void recordCompletion(GraphRunRequest request, GraphRunResult result) {
        String report = report(result);
        if (shortMemory != null) {
            shortMemory.addMessage(request.sessionId(), "USER: " + request.prompt());
            shortMemory.addMessage(request.sessionId(), "ASSISTANT: " + report);
        }
        if (longMemory != null) longMemory.record(request.sessionId(), report);
        if (eventPublisher != null) eventPublisher.publishEvent(new WorkflowFinishedEvent(this, request.sessionId()));
    }

    private String report(GraphRunResult result) {
        return result.artifacts().values().stream()
                .filter(artifact -> artifact.type() == ArtifactType.FINAL_REPORT)
                .map(Artifact::payload)
                .filter(com.financial.copilot.agent.core.dag.artifact.payload.FinalSynthesisReport.class::isInstance)
                .map(com.financial.copilot.agent.core.dag.artifact.payload.FinalSynthesisReport.class::cast)
                .map(com.financial.copilot.agent.core.dag.artifact.payload.FinalSynthesisReport::markdownReport)
                .findFirst().orElse("");
    }

}
