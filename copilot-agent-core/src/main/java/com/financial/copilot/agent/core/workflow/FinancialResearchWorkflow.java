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
import com.financial.copilot.common.event.ResearchStreamEvent;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Sole application entry for planning and executing financial research graphs. */
@Service
public class FinancialResearchWorkflow {
    private final ScreenerAgent screener;
    private final AnalyzerAgent analyzer;
    private final ComparatorAgent comparator;
    private final ReportSynthesizer synthesizer;
    private final ShortTermMemoryService shortMemory;
    private final LongTermMemoryService longMemory;
    private final ApplicationEventPublisher eventPublisher;
    private final GraphPlanner planner;
    private final DagRuntime runtime;
    private final GraphRunRegistry registry;
    private final DagCheckpointStore checkpointStore;

    @Autowired
    public FinancialResearchWorkflow(
            ScreenerAgent screener, AnalyzerAgent analyzer, ComparatorAgent comparator,
            ReportSynthesizer synthesizer,
            @Autowired(required = false) ShortTermMemoryService shortMemory,
            @Autowired(required = false) LongTermMemoryService longMemory,
            @Autowired(required = false) ApplicationEventPublisher eventPublisher,
            GraphPlanner planner,
            @Autowired(required = false) ResourceManager resources,
            @Autowired(required = false) DagCheckpointStore checkpointStore,
            @Autowired(required = false) NodeQualityGate qualityGate,
            @Autowired(required = false) ReplanPolicy replanPolicy,
            @Autowired(required = false) GraphRunRegistry registry) {
        this.screener = screener;
        this.analyzer = analyzer;
        this.comparator = comparator;
        this.synthesizer = synthesizer;
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
        return switch (node.getTaskType()) {
            case "SCREENING" -> screener.execute(node, input, context);
            case "BATCH_ANALYSIS" -> analyzer.execute(node, input, context);
            case "COMPARISON", "DEEP_DIVE" -> comparator.execute(node, input, context);
            case "SYNTHESIS" -> synthesizer.execute(node, input, context);
            case "MACRO" -> Artifact.of("art-" + node.getNodeId(), ArtifactType.MACRO_FACTS,
                    node.getNodeId(), Map.of("prompt", context.request().prompt(), "status", "available"));
            case "DATA_RAW" -> Artifact.of("art-" + node.getNodeId(), ArtifactType.DOCUMENT_EVIDENCE,
                    node.getNodeId(), node.getParams());
            default -> Artifact.of("art-" + node.getNodeId(), node.getOutputType(), node.getNodeId(), node.getParams());
        };
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

    // Temporary HTTP compatibility wrappers; controllers are migrated to run() in the next change.
    public String execute(String prompt) { return execute(null, prompt, false, null); }
    public String execute(String prompt, boolean thinking) { return execute(null, prompt, thinking, null); }
    public String execute(String prompt, boolean thinking, UserInvestmentProfile profile) { return execute(null, prompt, thinking, profile); }
    public String execute(String sessionId, String prompt, boolean thinking, UserInvestmentProfile profile) {
        return executeWithResult(sessionId, prompt, thinking, profile, null).getReport();
    }
    public WorkflowExecutionResult executeWithResult(String sessionId, String prompt, boolean thinking,
                                                     UserInvestmentProfile profile, Consumer<LlmResponse> usage) {
        String actualSession = sessionId == null ? UUID.randomUUID().toString() : sessionId;
        Long userId = profile != null && profile.getUserId() != null ? profile.getUserId() : 0L;
        long started = System.nanoTime();
        try {
            GraphRunResult result = run(new GraphRunRequest(UUID.randomUUID().toString(), userId, actualSession,
                    prompt, thinking, profile, usage, RunMode.SYNC)).completion().get(5, TimeUnit.MINUTES);
            return WorkflowExecutionResult.builder().sessionId(actualSession).runId(result.runId()).report(report(result))
                    .graphRevision(result.graph().getRevision())
                    .durationMs(Duration.ofNanos(System.nanoTime() - started).toMillis()).build();
        } catch (Exception e) {
            throw new IllegalStateException("Graph research failed", e);
        }
    }
    public Flux<ResearchStreamEvent> executePipelineStream(String prompt) { return executePipelineStream(null, prompt, false, null, null); }
    public Flux<ResearchStreamEvent> executePipelineStream(String prompt, boolean thinking) { return executePipelineStream(null, prompt, thinking, null, null); }
    public Flux<ResearchStreamEvent> executePipelineStream(String prompt, boolean thinking, UserInvestmentProfile profile) { return executePipelineStream(null, prompt, thinking, profile, null); }
    public Flux<ResearchStreamEvent> executePipelineStream(String sessionId, String prompt, boolean thinking, UserInvestmentProfile profile) { return executePipelineStream(sessionId, prompt, thinking, profile, null); }
    public Flux<ResearchStreamEvent> executePipelineStream(String sessionId, String prompt, boolean thinking,
                                                           UserInvestmentProfile profile, Consumer<LlmResponse> usage) {
        String actualSession = sessionId == null ? UUID.randomUUID().toString() : sessionId;
        Long userId = profile != null && profile.getUserId() != null ? profile.getUserId() : 0L;
        return run(new GraphRunRequest(UUID.randomUUID().toString(), userId, actualSession, prompt, thinking,
                profile, usage, RunMode.STREAM)).events();
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class WorkflowExecutionResult {
        private String sessionId;
        private String runId;
        private String report;
        private int graphRevision;
        private long durationMs;
    }
}
