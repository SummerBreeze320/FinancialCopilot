# Unified Dynamic Agent Graph Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace every production legacy plan/step/blackboard execution path with one user-isolated, dependency-driven, dynamically replannable Graph Runtime used by both synchronous and SSE requests.

**Architecture:** `FinancialResearchWorkflow.run(GraphRunRequest)` creates or resumes one `DagRunContext`; `GraphPlanner` directly creates the initial graph; `DagRuntime` owns all node scheduling and emits the sole event stream. Nodes consume explicit `InputBinding` values and return typed Artifacts, while Redis checkpoints persist the complete recoverable run.

**Tech Stack:** Java 21 virtual threads, Spring Boot 3.3.3 WebFlux, Reactor, Jackson 2.17, Redis, JUnit 5, Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-12-unified-dynamic-agent-graph-runtime-design.md`

## Global Constraints

- Keep existing research HTTP paths and principal response fields compatible.
- Remove all production references to `TaskDecomposer`, `ExecutionPlan`, `SubTask`, `LegacyPlanAdapter`, `BlackboardAdapter`, and `ResearchBlackboard` before completion.
- Use Java 21 directly; do not add Java 17 compatibility or another executor library.
- Do not add a JSONPath dependency; support `$` and Java record/bean/map field paths locally.
- Real LLM calls by Planner and Agents must forward the existing `Consumer<LlmResponse>` billing callback.
- Every run is owned by one authenticated user and has isolated queue, graph, state, ArtifactStore, cancellation token, and event stream.
- A2A, frontend graph rendering, distributed queueing, and arbitrary human graph editing remain outside scope.

---

### Task 1: Per-Run Runtime Context, Timeout, and Multi-Resource Scheduling

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/DagRunContext.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/GraphRunRequest.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/RunMode.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/GraphRunHandle.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/GraphRunResult.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/DagRuntime.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/resource/ResourceManager.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/resource/ResourceType.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/model/GraphNode.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/runtime/DagRunIsolationTest.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/runtime/DagRuntimeTest.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/runtime/resource/ResourceManagerTest.java`

**Interfaces:**
- Produces: `GraphRunHandle DagRuntime.run(GraphRunRequest request, ExecutionGraph graph)`.
- Produces: `DagRunContext` containing all mutable state for exactly one run.
- Produces: `Map<ResourceType,Integer> GraphNode.getResourceRequirements()`.

- [ ] **Step 1: Write failing isolation, empty-graph, timeout, and resource tests**

```java
@Test
void concurrentRunsNeverConsumeEachOthersReadyNodes() throws Exception {
    GraphRunHandle first = runtime.run(request("run-a"), oneNodeGraph("A"));
    GraphRunHandle second = runtime.run(request("run-b"), oneNodeGraph("B"));
    assertEquals(Set.of("A"), first.completion().get().artifacts().keySet());
    assertEquals(Set.of("B"), second.completion().get().artifacts().keySet());
}

@Test
void emptyGraphCompletesImmediately() throws Exception {
    assertTrue(runtime.run(request("empty"), new ExecutionGraph("empty"))
            .completion().get(500, MILLISECONDS).artifacts().isEmpty());
}

@Test
void timedOutNodeBecomesTimeoutAndReleasesEveryPermit() throws Exception {
    GraphNode node = node("slow", Duration.ofMillis(30), Map.of(LLM, 1, DPU, 1));
    GraphRunResult result = runtime.run(request("timeout"), graph(node)).completion().get();
    assertEquals(NodeStatus.TIMEOUT, result.nodeStatuses().get("slow"));
    assertEquals(1, resources.getAvailablePermits(LLM));
    assertEquals(1, resources.getAvailablePermits(DPU));
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
mvn -s maven-settings.xml "-Dmaven.repo.local=D:/.m2/repository" test "-Dtest=DagRunIsolationTest,DagRuntimeTest,ResourceManagerTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: failures because `DagRunContext`, `run(...)`, per-run queues, node timeout, and multi-resource requirements do not exist.

- [ ] **Step 3: Add the minimum run API and move mutable state into DagRunContext**

```java
public record GraphRunRequest(
        String runId, Long userId, String sessionId, String prompt,
        boolean enableThinking, UserInvestmentProfile profile,
        Consumer<LlmResponse> usageConsumer, RunMode mode) {}

public final class DagRunContext {
    private final ExecutionGraph graph;
    private final ArtifactStore artifacts = new ArtifactStore();
    private final ConcurrentMap<String, AtomicReference<NodeStatus>> statuses;
    private final PriorityReadyQueue readyQueue = new PriorityReadyQueue();
    private final CancellationToken cancellationToken;
    private final NodeEventBus events;
    private final CompletableFuture<GraphRunResult> completion = new CompletableFuture<>();
}

public record GraphRunHandle(
        String runId, Flux<ResearchStreamEvent> events,
        CompletableFuture<GraphRunResult> completion, Consumer<String> cancel) {}
```

Remove `readyQueue` and default `artifactStore` as mutable execution state from `DagRuntime`. `run()` must complete an empty graph immediately and use `CompletableFuture.orTimeout(node.getTimeout().toMillis(), MILLISECONDS)` or an equivalent virtual-thread future wrapper to enforce timeout.

- [ ] **Step 4: Acquire multiple resource permits atomically**

Sort resource types by enum order, check availability under one lock, acquire all or none, and release the exact acquired map in `finally`. Keep default limits Agent=8, LLM=4, DPU=10, RAG=20, MCP=10.

- [ ] **Step 5: Run tests and verify GREEN**

Run the Step 2 command. Expected: all selected tests pass with no leaked permits or hanging futures.

- [ ] **Step 6: Commit**

```powershell
git add copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag
git commit -m "refactor(runtime): isolate graph runs and enforce timeouts"
```

### Task 2: Explicit InputBinding and Atomic Runtime-Aware GraphPatch

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/model/InputBinding.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/NodeInput.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/NodeExecutionContext.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/model/GraphNode.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/model/ExecutionGraph.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/artifact/ArtifactStore.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/DependencyResolver.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/DagRuntime.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/NodeExecutor.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/model/InputBindingTest.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/model/ExecutionGraphTest.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/runtime/DynamicReplanTest.java`

**Interfaces:**
- Produces: `InputBinding(String name, String producerNodeId, ArtifactType expectedType, String path, boolean required)`.
- Produces: `NodeInput resolve(GraphNode node, ArtifactStore store)`.
- Changes: `NodeExecutor.execute(GraphNode, NodeInput, NodeExecutionContext)`.
- Produces: `ExecutionGraph copy()` and atomic `applyPatch(GraphPatch, Predicate<String> mutableNode)`.

- [ ] **Step 1: Write failing binding and Patch lifecycle tests**

```java
@Test
void bindingSelectsTheDeclaredProducerWhenTypesMatch() {
    store.store("semiconductor", artifact(FUND_POOL, pool("semi")));
    store.store("energy", artifact(FUND_POOL, pool("energy")));
    NodeInput input = resolver.resolve(nodeWith(binding("funds", "energy", FUND_POOL, "$.fundCodes")), store);
    assertEquals(List.of("energy"), input.require("funds", List.class));
}

@Test
void failedPatchLeavesGraphAndRevisionUnchanged() {
    ExecutionGraph before = graph.copy();
    assertThrows(IllegalStateException.class, () -> graph.applyPatch(cyclicPatch, id -> true));
    assertEquals(before.snapshot(), graph.snapshot());
}

@Test
void removeSkipAndRetryUpdateRunStateAndCompletionCount() throws Exception {
    GraphRunResult result = runtime.run(request, graphWithRuntimePatch()).completion().get(2, SECONDS);
    assertFalse(result.nodeStatuses().containsKey("removed"));
    assertEquals(SKIPPED, result.nodeStatuses().get("skipped"));
    assertEquals(SUCCEEDED, result.nodeStatuses().get("retried"));
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
mvn -s maven-settings.xml "-Dmaven.repo.local=D:/.m2/repository" test "-Dtest=InputBindingTest,ExecutionGraphTest,DynamicReplanTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: missing binding APIs and non-atomic/runtime-unaware Patch behavior.

- [ ] **Step 3: Implement binding resolution without a new dependency**

Support `$`, `$.field`, and nested `$.field.child` on Maps, records, and JavaBean getters. Reject a required missing value or mismatched Artifact type before setting READY. Optional missing inputs return `Optional.empty()` from `NodeInput.find(name)`.

- [ ] **Step 4: Make GraphPatch copy-validate-swap atomic**

Apply all operations to copied node and adjacency maps, validate uniqueness, references, bindings and acyclicity, then swap under `ExecutionGraph` synchronization and increment revision once. Reject changes to RUNNING or terminal nodes through the supplied predicate.

- [ ] **Step 5: Reconcile every Patch operation in DagRunContext**

ADD initializes PENDING and increments active count; REMOVE deletes an unstarted status and decrements it; SKIP changes PENDING/READY to SKIPPED and completes it; RETRY only changes FAILED/TIMEOUT to PENDING; UPDATE leaves status unchanged. Re-run dependency checks after the entire Patch commits.

- [ ] **Step 6: Run tests and verify GREEN**

Run the Step 2 command. Expected: all tests pass, including graph rollback equality and finite completion.

- [ ] **Step 7: Commit**

```powershell
git add copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag
git commit -m "feat(graph): add typed bindings and atomic patches"
```

### Task 3: Complete Checkpoint, Run Registry, and Ownership

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/GraphRunRegistry.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/model/ExecutionGraphSnapshot.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/checkpoint/DagCheckpoint.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/checkpoint/DagCheckpointStore.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/checkpoint/InMemoryDagCheckpointStore.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/checkpoint/RedisDagCheckpointStore.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/DagRuntime.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/runtime/checkpoint/DagCheckpointRoundTripTest.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/runtime/GraphRunRegistryTest.java`

**Interfaces:**
- Produces: `DagCheckpoint(String runId, Long userId, String sessionId, ExecutionGraphSnapshot graph, Map<String,NodeStatus> statuses, Map<String,Artifact<?>> artifacts, Instant savedAt)`.
- Produces: `Optional<DagCheckpoint> load(Long userId, String runId)`.
- Produces: `GraphRunHandle resume(Long userId, String runId, Consumer<LlmResponse> usageConsumer)`.

- [ ] **Step 1: Write failing serialization, new-runtime resume, and ownership tests**

```java
@Test
void checkpointRestoresArtifactsIntoANewRuntimeInstance() throws Exception {
    firstRuntime.run(request, graph).completion().get();
    GraphRunResult resumed = newRuntimeUsingSameStore.resume(userId, runId, usage).completion().get();
    assertEquals(pool, resumed.artifacts().get("screen").payload());
    assertEquals(1, executionsOfUnfinishedNode.get());
    assertEquals(0, executionsOfSuccessfulNode.get());
}

@Test
void anotherUserCannotLoadOrControlARun() {
    assertTrue(store.load(owner + 1, runId).isEmpty());
    assertFalse(registry.cancel(owner + 1, runId, "no access"));
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
mvn -s maven-settings.xml "-Dmaven.repo.local=D:/.m2/repository" test "-Dtest=DagCheckpointRoundTripTest,GraphRunRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: current checkpoint only stores Artifact IDs and has no ownership-aware resume.

- [ ] **Step 3: Persist an immutable graph snapshot and full Artifacts**

Use Jackson-visible DTO records instead of serializing mutable runtime objects. Store Redis keys as `copilot:dag:checkpoint:{userId}:{runId}`. Restore RUNNING and READY as PENDING; retain SUCCEEDED and SKIPPED with their Artifacts.

- [ ] **Step 4: Add GraphRunRegistry**

Keep active handles in `ConcurrentHashMap<RunKey,GraphRunHandle>`, where `RunKey` includes userId and runId. Remove completed handles after a short in-memory observation window; durable status remains in CheckpointStore.

- [ ] **Step 5: Run tests and verify GREEN**

Run the Step 2 command. Expected: a second Runtime instance resumes using serialized upstream payloads and rejects the wrong owner.

- [ ] **Step 6: Commit**

```powershell
git add copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag
git commit -m "feat(runtime): persist and resume owned graph runs"
```

### Task 4: Make GraphPlanner the Sole Initial Planner with Finite ReAct

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/PlannerAction.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/PlannerObservation.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/GraphPlan.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/GraphPlanningRequest.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/DeterministicGraphPlanner.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/tool/FinancialDocumentSearchTool.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/GraphPlanner.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/tool/MetricRAGTool.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/planner/GraphPlannerTest.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/planner/GraphPlannerBillingTest.java`

**Interfaces:**
- Produces: `ExecutionGraph plan(GraphPlanningRequest request)`.
- `GraphPlanningRequest` contains prompt, sessionId, profile and usageConsumer.
- The deterministic fallback returns the same ExecutionGraph contract and never calls an LLM.

- [ ] **Step 1: Write failing Planner behavior tests**

```java
@Test
void plannerBuildsIndependentThemeAndMacroBranchesBeforeJoin() {
    ExecutionGraph graph = planner.plan(request("比较半导体和新能源基金，结合宏观环境给出配置"));
    assertTrue(graph.getRootNodeIds().size() >= 3);
    assertTrue(graph.getNodes().values().stream().anyMatch(n -> "SYNTHESIS".equals(n.getTaskType())));
}

@Test
void plannerStopsAfterFourActionsAndFallsBackOnInvalidOutput() {
    ExecutionGraph graph = plannerWithAlwaysInvalidModel.plan(request("分析基金"));
    assertEquals(4, modelCalls.get());
    assertFalse(graph.getNodes().isEmpty());
}

@Test
void everyPlannerModelCallReportsUsage() {
    planner.plan(requestWithUsage(usages::add));
    assertEquals(modelCalls.get(), usages.size());
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
mvn -s maven-settings.xml "-Dmaven.repo.local=D:/.m2/repository" test "-Dtest=GraphPlannerTest,GraphPlannerBillingTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: current Planner is deterministic keyword logic, has no LLM ReAct contract, and always produces a serial graph.

- [ ] **Step 3: Implement the four-round Planner loop**

Each model response must be one structured action: `USE_METRIC_RAG`, `USE_SKILL_REGISTRY`, `USE_CAPABILITY_REGISTRY`, `SEARCH_DOCUMENTS`, `READ_MEMORY`, or `FINISH_GRAPH`. Reject unknown tools and invalid graph output. Append observations as structured JSON and stop immediately on valid `FINISH_GRAPH`.

- [ ] **Step 4: Implement deterministic fallback and document search adapter**

Reuse the existing fund report retrieval/data port rather than adding another search dependency. The fallback must create independent branches for independently requested themes/macro research and join them through explicit InputBindings.

- [ ] **Step 5: Keep runtime replan deterministic and bounded**

`planPatch()` may use one bounded Planner decision after `ReplanPolicy` triggers. If it fails, retain the existing empty-pool, single-candidate and missing-evidence rules. Never call Planner for ordinary successful nodes.

- [ ] **Step 6: Run tests and verify GREEN**

Run the Step 2 command. Expected: parallel graph, four-call cap, fallback, and usage propagation pass.

- [ ] **Step 7: Commit**

```powershell
git add copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/planner
git commit -m "feat(planner): build graphs through bounded react"
```

### Task 5: Migrate Agents to NodeInput and Bounded Local ReAct

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agents/react/AgentAction.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agents/react/AgentObservation.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agents/react/BoundedAgentLoop.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agents/ScreenerAgent.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agents/AnalyzerAgent.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agents/ComparatorAgent.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agents/ReportSynthesizer.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agents/fund/FundScreenerAgent.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agents/fund/FundAnalyzerAgent.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agents/fund/FundComparatorAgent.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/agents/AgentArtifactContractTest.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/agents/BoundedAgentLoopTest.java`

**Interfaces:**
- Agents consume `GraphNode`, `NodeInput`, and `NodeExecutionContext` and return one Artifact.
- `NodeExecutionContext` provides prompt, profile, cancellation, events and usageConsumer.
- `BoundedAgentLoop` allows at most five tool actions from an explicit tool map.

- [ ] **Step 1: Write failing exact-input and bounded-loop tests**

```java
@Test
void comparatorUsesOnlyItsBoundResearchArtifact() {
    NodeInput input = inputs(Map.of("research", semiconductorResearch));
    Artifact<ComparisonReport> result = comparator.execute(node, input, context);
    assertEquals(List.of("semi-a", "semi-b"), result.payload().fundCodes());
}

@Test
void agentCannotCallAnUnregisteredToolOrExceedFiveRounds() {
    assertThrows(IllegalArgumentException.class, () -> loop.act("delete_database"));
    loop.run(alwaysContinueModel);
    assertEquals(5, modelCalls.get());
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
mvn -s maven-settings.xml "-Dmaven.repo.local=D:/.m2/repository" test "-Dtest=AgentArtifactContractTest,BoundedAgentLoopTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: Agents still scan ArtifactStore by type and no bounded local loop exists.

- [ ] **Step 3: Convert deterministic nodes directly**

Screening and fixed metric calculations should read params/bindings and call their existing tools once. Do not wrap them in ReAct.

- [ ] **Step 4: Add bounded ReAct only to autonomous analysis/comparison**

Register only the existing quant, holdings, report and graph tools. After every observation, evaluate EvidenceContract; stop when sufficient or at five actions. Forward usageConsumer to every real LLM request. Return partial evidence metadata when the loop exhausts.

- [ ] **Step 5: Make synthesis consume declared inputs and publish chunks through context**

Remove `findFirstByType()` and blackboard fallback. Build factual context from the synthesis node's named inputs. For STREAM mode publish `content_chunk` and accumulate the same markdown stored in `FinalSynthesisReport`; for SYNC mode collect without emitting chunks.

- [ ] **Step 6: Run tests and verify GREEN**

Run the Step 2 command. Expected: exact producer selection, tool allowlist, five-round cap, typed outputs, and usage forwarding pass.

- [ ] **Step 7: Commit**

```powershell
git add copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agents copilot-agent-core/src/test/java/com/financial/copilot/agent/core/agents
git commit -m "refactor(agents): execute typed graph inputs with bounded react"
```

### Task 6: One Workflow Entry and Runtime-Driven SSE

**Files:**
- Rewrite: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/workflow/FinancialResearchWorkflow.java`
- Modify: `copilot-common/src/main/java/com/financial/copilot/common/event/ResearchStreamEvent.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/event/NodeEventBus.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/workflow/FinancialResearchWorkflowTest.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/workflow/UnifiedGraphEntryTest.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/workflow/WorkflowMeteringTest.java`

**Interfaces:**
- Produces: `GraphRunHandle FinancialResearchWorkflow.run(GraphRunRequest request)`.
- Existing `executeWithResult(...)` and `executePipelineStream(...)` become thin external-compatibility wrappers around `run()` until controllers migrate in Task 7, then are deleted.

- [ ] **Step 1: Write failing same-graph sync/SSE and event-source tests**

```java
@Test
void syncAndStreamUseTheSamePlannerAndRuntimeEntry() throws Exception {
    GraphRunResult sync = workflow.run(syncRequest).completion().get();
    GraphRunHandle stream = workflow.run(streamRequest);
    GraphRunResult streamed = stream.completion().get();
    assertEquals(sync.graph().topology(), streamed.graph().topology());
    verify(planner, times(2)).plan(any());
    verify(runtime, times(2)).run(any(), any());
}

@Test
void sseReportsActualRuntimeGraphRevisionAndArtifactIds() {
    List<ResearchStreamEvent> events = workflow.run(streamRequest).events().collectList().block();
    assertTrue(events.stream().anyMatch(e -> "graph_updated".equals(e.getType()) && e.getRevision() == 1));
    assertTrue(events.stream().anyMatch(e -> "node_completed".equals(e.getType()) && !e.getArtifactIds().isEmpty()));
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
mvn -s maven-settings.xml "-Dmaven.repo.local=D:/.m2/repository" test "-Dtest=UnifiedGraphEntryTest,FinancialResearchWorkflowTest,WorkflowMeteringTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: stream still executes a `for` loop and Runtime events are not the sole SSE source.

- [ ] **Step 3: Rewrite FinancialResearchWorkflow around run()**

The method retrieves relevant memory, calls `GraphPlanner.plan(...)`, supplies the NodeExecutor dispatcher, invokes `DagRuntime.run(...)`, and attaches one completion hook for memory recording and refinement. It must not contain a loop over nodes or steps.

- [ ] **Step 4: Map DagEvent to ResearchStreamEvent only in NodeEventBus**

Add `node_ready`, `node_failed`, `run_failed`, and `run_cancelled`. Emit graph descriptors from the actual planned graph, graph revisions from successful patches, Artifact IDs from stored results, and final duration from GraphRunResult.

- [ ] **Step 5: Preserve billed stream cancellation semantics**

On subscriber cancellation, mark the Run cancelled and prevent new nodes. If a metered LLM request is already streaming, suppress outgoing chunks but drain to usage completion before terminating its node.

- [ ] **Step 6: Run tests and verify GREEN**

Run the Step 2 command. Expected: sync and SSE have identical topology and Runtime-only events.

- [ ] **Step 7: Commit**

```powershell
git add copilot-agent-core/src/main/java/com/financial/copilot/agent/core/workflow copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/event copilot-common/src/main/java/com/financial/copilot/common/event copilot-agent-core/src/test/java/com/financial/copilot/agent/core/workflow
git commit -m "refactor(workflow): unify sync and stream graph execution"
```

### Task 7: Controller Migration and User-Owned Run Controls

**Files:**
- Modify: `copilot-app/src/main/java/com/financial/copilot/controller/ResearchAgentController.java`
- Create: `copilot-app/src/main/java/com/financial/copilot/controller/ResearchRunController.java`
- Modify: `copilot-app/src/main/java/com/financial/copilot/config/security/WebFluxSecurityConfig.java`
- Test: `copilot-app/src/test/java/com/financial/copilot/controller/ResearchAgentControllerTest.java`
- Create: `copilot-app/src/test/java/com/financial/copilot/controller/ResearchRunControllerTest.java`
- Modify: `copilot-app/src/test/java/com/financial/copilot/security/UserIsolationTest.java`

**Interfaces:**
- Controller calls only `workflow.run(request)` for new work.
- Produces: `GET /api/v1/research/runs/{runId}`.
- Produces: `POST /api/v1/research/runs/{runId}/cancel`.
- Produces: `POST /api/v1/research/runs/{runId}/resume`.

- [ ] **Step 1: Write failing controller compatibility and ownership tests**

```java
@Test
void syncAndSseControllersBothCallRun() {
    webClient.post().uri("/api/v1/research/chat").bodyValue(chat).exchange().expectStatus().isOk();
    webClient.get().uri(streamUri).exchange().expectStatus().isOk();
    verify(workflow, times(2)).run(any(GraphRunRequest.class));
}

@Test
void anotherUserSeesNotFoundForRunControls() {
    authenticatedAs(8).get().uri("/api/v1/research/runs/owned-by-7")
            .exchange().expectStatus().isNotFound();
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
mvn -s maven-settings.xml "-Dmaven.repo.local=D:/.m2/repository" test "-Dtest=ResearchAgentControllerTest,ResearchRunControllerTest,UserIsolationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: existing controllers call two workflow methods and run-control endpoints do not exist.

- [ ] **Step 3: Migrate existing endpoints**

Build GraphRunRequest using the authenticated user, scoped session key, profile, thinking flag, billing callback, and appropriate RunMode. Sync waits for completion; SSE returns handle.events(). Keep client-facing session IDs un-hashed in responses.

- [ ] **Step 4: Add owned status, cancel, and resume endpoints**

Resolve the current UID through `SecurityUtils.requireCurrentUserId(null)`. Registry/checkpoint lookup by `(uid, runId)` returns 404 for missing and foreign runs. Resume creates a new handle for the same runId and forwards the normal billing callback.

- [ ] **Step 5: Run tests and verify GREEN**

Run the Step 2 command. Expected: compatibility and ownership tests pass.

- [ ] **Step 6: Commit**

```powershell
git add copilot-app/src/main/java/com/financial/copilot/controller copilot-app/src/main/java/com/financial/copilot/config/security copilot-app/src/test/java/com/financial/copilot
git commit -m "feat(api): expose owned graph run controls"
```

### Task 8: Delete Legacy Execution Design and Complete Verification

**Files:**
- Delete: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/pipeline/TaskDecomposer.java`
- Delete: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/pipeline/ExecutionPlan.java`
- Delete: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/pipeline/SubTask.java`
- Delete: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/pipeline/ResearchBlackboard.java`
- Delete: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/prompt/TaskDecomposerPrompt.java`
- Delete: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/adapter/LegacyPlanAdapter.java`
- Delete: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/adapter/BlackboardAdapter.java`
- Delete: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/pipeline/TaskDecomposerTest.java`
- Delete: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/adapter/LegacyPlanAdapterTest.java`
- Delete: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/adapter/BlackboardAdapterTest.java`
- Create: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/architecture/NoLegacyExecutionArchitectureTest.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/llm/service/DefaultLlmService.java`
- Modify: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/agents/AgentArtifactContractTest.java`
- Modify: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/prompt/RTCFPromptSpecTest.java`
- Modify: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/workflow/FinancialResearchWorkflowTest.java`
- Modify: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/workflow/WorkflowMeteringTest.java`
- Modify: `copilot-app/src/test/java/com/financial/copilot/controller/ResearchAgentControllerTest.java`
- Modify: `README.md`

**Interfaces:**
- Produces no new API; proves the new Graph Runtime is the only production implementation.

- [ ] **Step 1: Add a structural test that fails while legacy references remain**

```java
@Test
void productionSourcesContainNoLegacyExecutionTypes() throws IOException {
    List<String> forbidden = List.of(
            "TaskDecomposer", "ExecutionPlan", "SubTask",
            "ResearchBlackboard", "LegacyPlanAdapter", "BlackboardAdapter");
    String production = readAllJavaUnder("src/main/java");
    forbidden.forEach(name -> assertFalse(production.contains(name), name));
}
```

- [ ] **Step 2: Run the structural test and verify RED**

Run:

```powershell
mvn -s maven-settings.xml "-Dmaven.repo.local=D:/.m2/repository" test "-Dtest=NoLegacyExecutionArchitectureTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: failure listing all remaining old production references.

- [ ] **Step 3: Delete old classes and old-only overloads**

Remove the six legacy production types, old adapters, `executeStep`, blackboard fallback code, old planning tests, and compatibility constructors no longer used. Update remaining tests to create ExecutionGraph or GraphRunRequest directly.

- [ ] **Step 4: Update documentation**

Document the unified lifecycle `Controller -> Workflow.run -> GraphPlanner -> DagRuntime -> Artifact -> Planner Patch`, node SSE event contract, run controls, Java 21 requirement, resource defaults and Redis recovery behavior. Remove README descriptions of sequential steps as the runtime architecture.

- [ ] **Step 5: Run all unit tests**

Run:

```powershell
mvn -s maven-settings.xml "-Dmaven.repo.local=D:/.m2/repository" test
```

Expected: reactor BUILD SUCCESS with zero failures and errors.

- [ ] **Step 6: Run PostgreSQL and Redis integration tests**

Set the existing local database and Redis environment variables without printing secrets, then run:

```powershell
mvn -s maven-settings.xml "-Dmaven.repo.local=D:/.m2/repository" test "-Dcopilot.integration=true"
```

Expected: application startup, memory, billing transaction, concurrent payment, Redis checkpoint round-trip, resume, and user isolation tests all pass without a remote LLM call.

- [ ] **Step 7: Verify deletion and diff quality**

Run:

```powershell
rg -n "TaskDecomposer|ExecutionPlan|SubTask|ResearchBlackboard|LegacyPlanAdapter|BlackboardAdapter" --glob "*.java"
git diff --check
git status --short
```

Expected: `rg` has no production/test Java matches, `git diff --check` is clean, and status contains only intended changes.

- [ ] **Step 8: Commit**

```powershell
git add README.md copilot-agent-core copilot-app copilot-common
git commit -m "refactor: remove legacy research execution architecture"
```
