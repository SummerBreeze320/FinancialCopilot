package com.financial.copilot.agent.core.workflow;

import com.financial.copilot.agent.core.agents.*;
import com.financial.copilot.agent.core.dag.artifact.*;
import com.financial.copilot.agent.core.dag.guard.*;
import com.financial.copilot.agent.core.dag.model.*;
import com.financial.copilot.agent.core.dag.planner.*;
import com.financial.copilot.agent.core.dag.runtime.*;
import com.financial.copilot.agent.core.dag.runtime.checkpoint.*;
import com.financial.copilot.agent.core.dag.runtime.resource.ResourceManager;
import com.financial.copilot.agent.core.event.WorkflowFinishedEvent;
import com.financial.copilot.agent.core.llm.dto.LlmResponse;
import com.financial.copilot.agent.core.memory.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.function.Consumer;

/** Sole application entry for planning and executing financial research graphs. */
@Service
public class FinancialResearchWorkflow {
    private final AgentNodeRouter agentRouter;
    private final GraphPlannerAgent planner;
    private final DagRuntime runtime;
    private final GraphRunRegistry registry;
    private final DagCheckpointStore checkpointStore;

    @Autowired
    public FinancialResearchWorkflow(
            AgentNodeRouter agentRouter,
            GraphPlannerAgent planner,
            @Autowired(required = false) ResourceManager resources,
            @Autowired(required = false) DagCheckpointStore checkpointStore,
            @Autowired(required = false) NodeQualityGate qualityGate,
            @Autowired(required = false) ReplanPolicy replanPolicy,
            @Autowired(required = false) GraphRunRegistry registry) {
        this.agentRouter = agentRouter;
        this.planner = planner;
        this.registry = registry == null ? new GraphRunRegistry() : registry;
        this.checkpointStore = checkpointStore == null ? new InMemoryDagCheckpointStore() : checkpointStore;
        NodeExecutor dispatcher = this::dispatch;
        this.runtime = new DagRuntime(dispatcher,
                resources == null ? ResourceManager.defaultManager() : resources,
                this.checkpointStore,
                qualityGate == null ? new DefaultNodeQualityGate() : qualityGate,
                replanPolicy == null ? ReplanPolicy.heuristic() : replanPolicy,
                planner);
    }

    public GraphRunHandle run(GraphRunRequest request) {
        ExecutionGraph graph = planner.plan(new GraphPlanningRequest(request.prompt(), request.sessionKey(),
                request.profile(), request.usageConsumer(), request.enableThinking(), request));
        GraphRunHandle handle = registry.register(request.userId(), runtime.run(request, graph));
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

    @PreDestroy
    void closeRuntime() {
        runtime.close();
    }

}
