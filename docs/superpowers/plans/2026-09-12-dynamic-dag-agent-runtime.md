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

### Task 1: Core Graph Models & Lifecycle State Machine (★★★★★)

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/model/NodeStatus.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/model/FailurePolicy.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/model/GraphNode.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/model/ExecutionGraph.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/model/ExecutionGraphTest.java`

**Interfaces:**
- Produces:
  - `NodeStatus`: `PENDING`, `READY`, `RUNNING`, `SUCCEEDED`, `FAILED`, `SKIPPED`, `CANCELLED`, `TIMEOUT`
  - `FailurePolicy`: `FAIL_FAST`, `CONTINUE`, `RETRY`, `FALLBACK`, `OPTIONAL`
  - `ExecutionGraph`: `addNode(GraphNode)`, `addEdge(from, to)`, `removeNode(nodeId)`, `hasCycle(): boolean`, `getRootNodeIds(): Set<String>`, `calculateTopologicalWave(nodeId): int`

- [ ] **Step 1: Write the failing unit tests for `ExecutionGraph`**
  - Test cycle detection (A -> B -> A throws IllegalStateException).
  - Test topological wave computation (root = 0, child = parent + 1, diamond = max(parents) + 1).
  - Test dynamic node addition and removal (edges cascade cleanly).
- [ ] **Step 2: Run test to confirm it fails**
  - Run `mvn test -pl copilot-agent-core -Dtest=ExecutionGraphTest`
- [ ] **Step 3: Implement `NodeStatus`, `FailurePolicy`, `GraphNode`, and `ExecutionGraph`**
  - Thread-safe `ConcurrentHashMap` for `nodes`, `upstream`, and `downstream`.
  - Kahn's algorithm for `hasCycle()`.
- [ ] **Step 4: Run tests to ensure they pass**
  - Verify with `mvn test -pl copilot-agent-core -Dtest=ExecutionGraphTest`
- [ ] **Step 5: Commit changes**
  - `git commit -m "feat(dag): add ExecutionGraph, GraphNode, NodeStatus, and FailurePolicy"`

---

### Task 2: Multi-Modal Typed Artifact Model & Storage (★★★★☆)

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/artifact/ArtifactType.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/artifact/ArtifactMetadata.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/artifact/Artifact.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/artifact/ArtifactStore.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/artifact/ArtifactStoreTest.java`

**Interfaces:**
- Produces:
  - `ArtifactType`: `FUND_POOL`, `STOCK_POOL`, `MACRO_FACTS`, `METRICS_MATRIX`, `COMPARISON_REPORT`, `ALLOCATION_ADVICE`, `FINAL_SYNTHESIS_REPORT`, `GENERIC`
  - `Artifact<T>` record: `artifactId`, `producerNodeId`, `type`, `text`, `structuredData`, `components`, `references`, `evidences`, `metadata`
  - `ArtifactStore`: `store(nodeId, artifact)`, `get(nodeId): Artifact<T>`, `getAllUpstream(upstreamIds): Map<String, Artifact<?>>`, `putGlobalContext(key, val)`

- [ ] **Step 1: Write the failing unit tests for `ArtifactStore`**
  - Test storing and typed retrieval of artifacts.
  - Test resolving upstream artifacts for a node with multiple dependencies.
  - Test degradation flags (`evidenceIncomplete=true`).
- [ ] **Step 2: Run test to confirm it fails**
  - Run `mvn test -pl copilot-agent-core -Dtest=ArtifactStoreTest`
- [ ] **Step 3: Implement `ArtifactType`, `ArtifactMetadata`, `Artifact`, and `ArtifactStore`**
- [ ] **Step 4: Run tests and ensure they pass**
  - Verify with `mvn test -pl copilot-agent-core -Dtest=ArtifactStoreTest`
- [ ] **Step 5: Commit changes**
  - `git commit -m "feat(dag): add multi-modal Artifact and ArtifactStore"`

---

### Task 3: Resource-Aware ConcurrencyLimiter (★★★★☆)

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/ConcurrencyLimiter.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/runtime/ConcurrencyLimiterTest.java`

**Interfaces:**
- Produces:
  - `ConcurrencyLimiter`: `runWithAgentPermit(Callable<T>)`, `runWithLlmPermit(Callable<T>)`, `runWithDataPortPermit(Callable<T>)`
  - Configurable permits: default Agent=8, LLM=4, DataPort=10

- [ ] **Step 1: Write unit tests verifying permit limits and virtual thread unmounting**
  - Test concurrent executions capping at max permits.
- [ ] **Step 2: Run test to confirm it fails**
  - Run `mvn test -pl copilot-agent-core -Dtest=ConcurrencyLimiterTest`
- [ ] **Step 3: Implement `ConcurrencyLimiter` using Java `Semaphore`**
- [ ] **Step 4: Run tests and ensure they pass**
  - Verify with `mvn test -pl copilot-agent-core -Dtest=ConcurrencyLimiterTest`
- [ ] **Step 5: Commit changes**
  - `git commit -m "feat(dag): add resource-aware ConcurrencyLimiter for virtual threads"`

---

### Task 4: Dependency-Driven DAG Execution Engine (★★★★★)

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/DagEvent.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/NodeExecutor.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/DependencyResolver.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/DagRuntime.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/runtime/DagRuntimeTest.java`

**Interfaces:**
- Consumes: `ExecutionGraph`, `GraphNode`, `NodeStatus`, `ArtifactStore`, `ConcurrencyLimiter`
- Produces:
  - `DagRuntime.executeGraph(graph, eventConsumer): CompletableFuture<Void>`
  - Event-driven node completion: `onNodeCompleted` -> checks `graph.getDownstream()` -> if all `upstream` in terminal success/skip, CAS transitions to `READY` and immediately dispatches on Java 21 virtual threads.
  - Zero runtime wavefront barrier: Downstream starts the instant its own dependencies succeed, regardless of straggler nodes in other branches.

- [ ] **Step 1: Write unit tests for diamond dependency, straggler non-blocking, and failure policies**
  - Diamond DAG: A -> [B (slow 500ms), C (fast 50ms)] -> D (depends on C only!). Verify D runs at 50ms without waiting for B.
  - FailurePolicy tests: `CONTINUE` passes degraded artifact; `OPTIONAL` skips gracefully; `FAIL_FAST` fails graph.
- [ ] **Step 2: Run test to confirm it fails**
  - Run `mvn test -pl copilot-agent-core -Dtest=DagRuntimeTest`
- [ ] **Step 3: Implement `DagEvent`, `NodeExecutor`, `DependencyResolver`, and `DagRuntime`**
  - Native virtual thread pool: `Executors.newVirtualThreadPerTaskExecutor()`.
  - Atomic CAS state transitions (`AtomicReference<NodeStatus>`).
  - Active node countdown for completion.
- [ ] **Step 4: Run tests and ensure they pass**
  - Verify with `mvn test -pl copilot-agent-core -Dtest=DagRuntimeTest`
- [ ] **Step 5: Commit changes**
  - `git commit -m "feat(dag): implement event-driven DagRuntime with Java 21 virtual threads"`

---

### Task 5: Node-Level SSE Event Protocol & Streaming Adapter (★★★★☆)

**Files:**
- Modify: `copilot-common/src/main/java/com/financial/copilot/common/agent/dto/ResearchStreamEvent.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/event/NodeEventBus.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/event/NodeEventBusTest.java`

**Interfaces:**
- Produces:
  - `ResearchStreamEvent`: enhanced with node lifecycle event payloads (`graph_initialized`, `node_started`, `node_completed`, `graph_updated`, `content_chunk`, `run_completed`).
  - `NodeEventBus`: adapts `DagEvent` into reactive `Flux<ResearchStreamEvent>`.

- [ ] **Step 1: Write unit tests for event streaming and serialization**
- [ ] **Step 2: Run test to confirm it fails**
- [ ] **Step 3: Extend `ResearchStreamEvent` and implement `NodeEventBus`**
- [ ] **Step 4: Run tests and verify reactive SSE Flux emissions**
- [ ] **Step 5: Commit changes**
  - `git commit -m "feat(dag): add node-level SSE streaming events and NodeEventBus"`

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

### Task 7: Dynamic Re-planning Checkpoint & ReAct Planner Integration (★★★☆☆)

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/RePlanAdvisor.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/tool/MetricRAGTool.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/tool/SkillRegistryTool.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/GraphPlanner.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/runtime/DynamicReplanTest.java`

**Interfaces:**
- Produces:
  - `RePlanAdvisor`: inspects completed node artifacts and issues delta mutations (`KEEP`, `ADD_NODE`, `MODIFY_NODE`, `PRUNE`).
  - `GraphPlanner`: uses `MetricRAGTool` and `SkillRegistryTool` to generate structured `ExecutionGraph`.

- [ ] **Step 1: Write unit tests for dynamic graph mutation mid-flight (adding a node after node 1 completes)**
- [ ] **Step 2: Run test to confirm it fails**
- [ ] **Step 3: Implement `RePlanAdvisor`, planner tools, and dynamic graph updating**
- [ ] **Step 4: Run tests and ensure they pass**
  - Verify with `mvn test -pl copilot-agent-core -Dtest=DynamicReplanTest`
- [ ] **Step 5: Commit changes**
  - `git commit -m "feat(planner): add dynamic re-planning advisor and tools"`

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
