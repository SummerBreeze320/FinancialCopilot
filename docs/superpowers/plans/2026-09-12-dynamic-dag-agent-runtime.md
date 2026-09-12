# Dynamic Multi-Agent DAG Runtime Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a production-grade, event-driven, dependency-directed DAG execution engine native to Java 21 LTS virtual threads, with typed multi-modal artifacts, node-level failure policies, concurrency control, and dynamic re-planning capability.

**Architecture:** Adopts a 4-tier separation of concerns: Planner (WHAT/WHY/Contract) -> DAG Runtime (WHEN/Concurrency/Flow via ReadyQueue & DependencyResolver) -> Agent (HOW via Local ReAct) -> Tools (Atomic I/O & Compute). Waves are purely static layout metadata for UI/explanation, with zero runtime barrier.

**Tech Stack:** Java 21 LTS (`Executors.newVirtualThreadPerTaskExecutor()`, Record Patterns, Pattern Matching), Spring Boot 3.3.3, Project Reactor WebFlux, JUnit 5, AssertJ, Mockito.

**Spec:** [docs/superpowers/specs/2026-09-12-dag-runtime-engine-spec.md](file:///d:/BaiduSyncdisk/IdeaProjects/FinancialCopilot/docs/superpowers/specs/2026-09-12-dag-runtime-engine-spec.md)

## Global Constraints
- Target runtime: Pure Java 21 LTS. No Java 17 reflection or fallback executor abstractions.
- Zero external heavy workflow dependencies (no Camunda/LiteFlow). Pure native Java 21 + Spring.
- Zero regression: All existing 57+ tests in `copilot-agent-core` and 28+ tests in `copilot-app` must pass without breaking changes.
- Concurrency safety: Virtual threads must be throttled via `ConcurrencyLimiter` semaphores to protect downstream LLM and database resources.

---

### Task 1: Core Graph Models, GraphPatch & Lifecycle State Machine (★★★★★)

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/model/NodeStatus.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/model/FailurePolicy.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/model/GraphNode.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/model/patch/PatchOp.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/model/patch/GraphOperation.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/model/patch/GraphPatch.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/model/ExecutionGraph.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/model/ExecutionGraphTest.java`

**Interfaces:**
- Produces:
  - `NodeStatus`: `PENDING`, `READY`, `RUNNING`, `SUCCEEDED`, `FAILED`, `SKIPPED`, `CANCELLED`, `TIMEOUT`
  - `FailurePolicy`: `FAIL_FAST`, `CONTINUE`, `RETRY`, `FALLBACK`, `OPTIONAL`
  - `PatchOp`: `ADD_NODE`, `REMOVE_NODE`, `ADD_EDGE`, `REMOVE_EDGE`, `UPDATE_NODE`, `SKIP_NODE`, `RETRY_NODE`
  - `GraphPatch`: `baseRevision`, `List<GraphOperation>`
  - `ExecutionGraph`: `addNode`, `addEdge`, `removeNode`, `applyPatch(GraphPatch): int`, `hasCycle(): boolean`, `calculateTopologicalWave(nodeId): int`

- [ ] **Step 1: Write the failing unit tests for `ExecutionGraph` and `GraphPatch`**
  - Test cycle detection (A -> B -> A throws IllegalStateException).
  - Test topological wave computation.
  - Test `applyPatch`: optimistic locking via `baseRevision` and atomic operations (`ADD_NODE`, `ADD_EDGE`, `SKIP_NODE`).
- [ ] **Step 2: Run test to confirm it fails**
  - Run `mvn test -pl copilot-agent-core -Dtest=ExecutionGraphTest`
- [ ] **Step 3: Implement `NodeStatus`, `FailurePolicy`, `GraphNode`, `GraphPatch`, and `ExecutionGraph`**
- [ ] **Step 4: Run tests to ensure they pass**
  - Verify with `mvn test -pl copilot-agent-core -Dtest=ExecutionGraphTest`
- [ ] **Step 5: Commit changes**
  - `git commit -m "feat(dag): add ExecutionGraph, GraphNode, NodeStatus, and GraphPatch"`

---

### Task 2: Typed Artifact Contract & Storage (★★★★☆)

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/artifact/ArtifactType.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/artifact/ArtifactMetadata.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/artifact/Artifact.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/artifact/ArtifactStore.java`
- Create domain payloads in: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/artifact/payload/` (`FundPool`, `MacroResearchResult`, `FundResearchResult`, `ComparisonReport`, `DocumentEvidence`)
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/artifact/ArtifactStoreTest.java`

**Interfaces:**
- Produces:
  - `Artifact<T>(id, type, producerNodeId, payload, metadata)`
  - `ArtifactMetadata(createdAt, schemaVersion, evidenceIds, confidence, partial, source)`
  - Standardized payloads: `FundPool`, `FundResearchResult`, `MacroResearchResult`, `ComparisonReport`, `DocumentEvidence`
  - `ArtifactStore`: `store(nodeId, artifact)`, `get(nodeId): Artifact<T>`, `getAllUpstream(upstreamIds): Map<String, Artifact<?>>`, `putGlobalContext(key, val)`

- [ ] **Step 1: Write the failing unit tests for `ArtifactStore` and typed contracts**
  - Test storing and strongly typed retrieval of `Artifact<FundPool>` and `Artifact<FundResearchResult>`.
  - Test resolving upstream artifacts for a node with multiple dependencies.
  - Test auditing metadata (`evidenceIds`, `confidence`, `partial=true`).
- [ ] **Step 2: Run test to confirm it fails**
  - Run `mvn test -pl copilot-agent-core -Dtest=ArtifactStoreTest`
- [ ] **Step 3: Implement `Artifact`, `ArtifactMetadata`, domain payloads, and `ArtifactStore`**
- [ ] **Step 4: Run tests and ensure they pass**
  - Verify with `mvn test -pl copilot-agent-core -Dtest=ArtifactStoreTest`
- [ ] **Step 5: Commit changes**
  - `git commit -m "feat(dag): add Typed Artifact Contract, ArtifactMetadata, and ArtifactStore"`

---

### Task 3: Resource-Aware Scheduling & ResourceManager (★★★★☆)

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/resource/ResourceType.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/resource/NodePriority.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/resource/ResourceRequirement.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/resource/ResourceManager.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/runtime/resource/ResourceManagerTest.java`

**Interfaces:**
- Produces:
  - `ResourceType`: `LLM`, `DPU`, `RAG`, `MCP`, `COMPONENT`
  - `NodePriority`: `HIGH(10)`, `NORMAL(5)`, `LOW(1)`
  - `ResourceRequirement(ResourceType, permits)`
  - `ResourceManager`: `tryAcquire(req)`, `acquire(req)`, `release(req)`
  - Default quotas: LLM=4, DPU=10, RAG=20, MCP=10, Component=8

- [ ] **Step 1: Write unit tests verifying quota limits and semaphore unmounting**
  - Test concurrent executions capping at declared quota per resource type.
  - Test release permits wakes up blocked/waiting requests.
- [ ] **Step 2: Run test to confirm it fails**
  - Run `mvn test -pl copilot-agent-core -Dtest=ResourceManagerTest`
- [ ] **Step 3: Implement `ResourceType`, `NodePriority`, `ResourceRequirement`, and `ResourceManager`**
- [ ] **Step 4: Run tests and ensure they pass**
  - Verify with `mvn test -pl copilot-agent-core -Dtest=ResourceManagerTest`
- [ ] **Step 5: Commit changes**
  - `git commit -m "feat(dag): add declarative ResourceManager with multi-resource quotas"`

---

### Task 4: Dependency-Driven DAG Execution Engine (★★★★★)

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/context/CancellationToken.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/DagEvent.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/NodeExecutor.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/DependencyResolver.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/PriorityReadyQueue.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/checkpoint/DagCheckpoint.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/checkpoint/DagCheckpointStore.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/checkpoint/InMemoryDagCheckpointStore.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/checkpoint/RedisDagCheckpointStore.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/DagRuntime.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/runtime/DagRuntimeTest.java`

**Interfaces:**
- Consumes: `ExecutionGraph`, `GraphNode`, `NodeStatus`, `ArtifactStore`, `ResourceManager`
- Produces:
  - `CancellationToken`: Tree-structured hierarchical token (`createChild(scopeId)`, `bindCurrentThread(): AutoCloseable`, `cancel(reason)`, `isCancelled()`, `throwIfCancelled()`, `onCancel(callback)`).
  - `DagCheckpoint` & `DagCheckpointStore`: Atomic state snapshotting on every `NodeSucceeded`, persisting `runId`, `revision`, `nodeStatuses`, and `artifactIds`.
  - `DagRuntime.executeGraph(graph, cancellationToken, eventConsumer): CompletableFuture<Void>`
  - `DagRuntime.resume(runId, cancellationToken, eventConsumer): CompletableFuture<Void>` (re-hydrates graph from checkpoint, fast-forwards SUCCEEDED nodes without re-execution, enqueues READY dependents).
  - Event-driven node completion: `onNodeCompleted` -> persists artifact -> writes checkpoint -> checks `graph.getDownstream()` -> if all `upstream` in terminal success/skip, pushes to `PriorityReadyQueue`.
  - Resource gate: `ResourceManager.tryAcquire()` -> if success dispatches on virtual threads; if quota full waits in `PriorityReadyQueue` until permit freed.
  - Structured Cancellation: When `runToken.cancel()` triggers, recursively cascades to all child tokens (Node -> Agent -> Tool), interrupts all bound virtual threads, executes I/O abort callbacks, immediately reclaims held permits in `ResourceManager`, and marks unexecuted/running nodes as `CANCELLED`.
  - Child Isolation: A child token's individual cancellation (e.g. single node timeout) does not leak to parent or siblings unless policy is `FAIL_FAST`.
  - Zero runtime wavefront barrier: Downstream starts the instant its own dependencies succeed and resource permit is available.

- [ ] **Step 1: Write unit tests for diamond dependency, priority scheduling, failure policies, and tree-structured cancellation propagation**
  - Diamond DAG: A -> [B (slow 500ms), C (fast 50ms)] -> D (depends on C only!). Verify D runs at 50ms without waiting for B.
  - Priority test: When LLM quota is 1, HIGH priority node runs before NORMAL priority node.
  - Tree Cancellation test: Trigger root `runToken.cancel()` mid-run; verify recursive child cancellation, virtual thread interruption, permits released, pending nodes aborted.
  - Child Cancellation Isolation test: Child node timeout cancels node and interrupts its agent/tool without cancelling parent run or sibling nodes.
  - FailurePolicy tests: `CONTINUE` passes degraded artifact; `OPTIONAL` skips gracefully; `FAIL_FAST` fails graph.
  - Checkpoint & Resume test: Run A -> B -> C -> D; simulate failure at D; invoke `dagRuntime.resume(runId)`; verify A, B, C are NOT executed again, their artifacts are loaded from store, D executes and pipeline finishes.
- [ ] **Step 2: Run test to confirm it fails**
  - Run `mvn test -pl copilot-agent-core -Dtest=DagRuntimeTest`
- [ ] **Step 3: Implement `CancellationToken`, `DagEvent`, `NodeExecutor`, `DependencyResolver`, `PriorityReadyQueue`, and `DagRuntime`**
  - Native virtual thread pool: `Executors.newVirtualThreadPerTaskExecutor()`.
  - Atomic CAS state transitions (`AtomicReference<NodeStatus>`).
  - Active node countdown for completion.
- [ ] **Step 4: Run tests and ensure they pass**
  - Verify with `mvn test -pl copilot-agent-core -Dtest=DagRuntimeTest`
- [ ] **Step 5: Commit changes**
  - `git commit -m "feat(dag): implement event-driven DagRuntime with PriorityReadyQueue, CancellationToken, and ResourceManager"`

---

### Task 5: Node-Level SSE Event Protocol & Streaming Adapter (★★★★☆)

**Files:**
- Modify: `copilot-common/src/main/java/com/financial/copilot/common/agent/dto/ResearchStreamEvent.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/event/NodeEventBus.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/event/NodeEventBusTest.java`

**Interfaces:**
- Produces:
  - `ResearchStreamEvent`: enhanced with node lifecycle event payloads (`graph_initialized`, `node_started`, `node_completed`, `graph_updated`, `content_chunk`, `run_completed`).
  - `NodeEventBus`: adapts `DagEvent` into reactive `Flux<ResearchStreamEvent>`, hooks client disconnect via `flux.doOnCancel(() -> token.cancel("SSE Client Disconnected"))`.

- [ ] **Step 1: Write unit tests for event streaming, serialization, and cancel-hook**
- [ ] **Step 2: Run test to confirm it fails**
- [ ] **Step 3: Extend `ResearchStreamEvent` and implement `NodeEventBus`**
- [ ] **Step 4: Run tests and verify reactive SSE Flux emissions and disconnect cancellation**
- [ ] **Step 5: Commit changes**
  - `git commit -m "feat(dag): add node-level SSE streaming events, NodeEventBus, and client disconnect cancellation"`

---

### Task 6: Legacy Plan Adapter & Workflow Integration (★★★★☆)

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/adapter/LegacyPlanAdapter.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/adapter/BlackboardAdapter.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/workflow/FinancialResearchWorkflow.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/workflow/FinancialResearchWorkflowTest.java`

**Interfaces:**
- Consumes: Existing `ExecutionPlan`, `SubTask`, `ResearchBlackboard`
- Produces:
  - Converts `ExecutionPlan` to `ExecutionGraph`.
  - Replaces linear `for (SubTask step : plan.getSteps())` with `dagRuntime.executeGraph(...)`.
  - Supports both synchronous `execute(...)` and reactive `executePipelineStream(...)`.

- [ ] **Step 1: Write regression and integration tests verifying 4-step workflow runs via DagRuntime**
- [ ] **Step 2: Implement `LegacyPlanAdapter` and `BlackboardAdapter`**
- [ ] **Step 3: Refactor `FinancialResearchWorkflow` to delegate execution to `DagRuntime`**
- [ ] **Step 4: Run all existing 57+ tests in `copilot-agent-core` to verify 100% backward compatibility**
  - Run `mvn test -pl copilot-agent-core`
- [ ] **Step 5: Commit changes**
  - `git commit -m "refactor(workflow): integrate DagRuntime into FinancialResearchWorkflow"`

---

### Task 7: ReplanPolicy & Dynamic GraphPatch Integration (★★★☆☆)

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/ReplanPolicy.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/RePlanAdvisor.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/tool/MetricRAGTool.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/tool/SkillRegistryTool.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/GraphPlanner.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/runtime/DynamicReplanTest.java`

**Interfaces:**
- Produces:
  - `ReplanPolicy`: evaluates whether a completed node requires re-planning (Fast-Path: bypass LLM; Adaptive-Path: trigger ReAct Planner)
  - `RePlanAdvisor`: inspects completed node artifacts and generates `GraphPatch` (delta mutations: `ADD_NODE`, `ADD_EDGE`, `SKIP_NODE`)
  - `GraphPlanner`: uses `MetricRAGTool` and `SkillRegistryTool` to construct `ExecutionGraph` and `GraphPatch`

- [ ] **Step 1: Write unit tests verifying conditional replan triggering and GraphPatch application**
  - Test normal node output passes directly without invoking RePlanAdvisor (zero overhead).
  - Test partial/empty candidate output triggers RePlanAdvisor and generates `GraphPatch`.
  - Test `GraphPatch` modifies graph from Revision 1 -> 2 mid-flight, and newly added node executes automatically.
- [ ] **Step 2: Run test to confirm it fails**
- [ ] **Step 3: Implement `ReplanPolicy`, `RePlanAdvisor`, planner tools, and `GraphPatch` application**
- [ ] **Step 4: Run tests and ensure they pass**
  - Verify with `mvn test -pl copilot-agent-core -Dtest=DynamicReplanTest`
- [ ] **Step 5: Commit changes**
  - `git commit -m "feat(planner): add ReplanPolicy, GraphPatch generation, and dynamic planner tools"`

---

### Task 8: Full-Stack Verification & Maven Multi-Module Build (★★★★★)

**Files:**
- Test: `copilot-app/src/test/java/com/financial/copilot/controller/ResearchAgentControllerTest.java`
- Test: Full repository build across all 8 modules

- [ ] **Step 1: Run full Maven test suite across all 8 modules**
  - Run `mvn clean test`
- [ ] **Step 2: Verify zero regression in billing, user isolation, and agent tests**
- [ ] **Step 3: Commit final integration state**
  - `git commit -m "test(dag): verify full test suite passes on Java 21 with dynamic DAG runtime"`
