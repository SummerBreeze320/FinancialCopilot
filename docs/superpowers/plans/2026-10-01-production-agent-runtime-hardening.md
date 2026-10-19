# Production Agent Runtime Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the unified financial-research workflow safe under concurrent users and multiple application instances while enforcing user-scoped memory, explicit AgentScope context propagation, bounded model/tool capacity, fair scheduling, durable recovery, and concurrency-safe billing.

**Architecture:** Keep `FinancialResearchWorkflow.run(...)` as the sole research entry, the custom dynamic DAG as the workflow runtime, and AgentScope `ReActAgent` as the node executor. Introduce an immutable execution identity and conversation snapshot, a global fair scheduler, a single model-execution gateway, role-scoped immutable toolkits, user-scoped memory namespaces, PostgreSQL-backed run leases/events, Redis-optional acceleration, and wallet reservation/settlement.

**Tech Stack:** Java 21, Maven, Spring Boot 3.3.3, WebFlux, AgentScope Java 2.0.0, PostgreSQL 16, MyBatis-Plus 3.5.7, Redis optional locally and required for production acceleration, Python 3.12/FastAPI memory-service, Graphiti/Neo4j, Reactor, Micrometer, JUnit 5, Mockito, AssertJ, pytest.

**Spec:** `docs/superpowers/specs/2026-09-12-unified-dynamic-agent-graph-runtime-design.md`, `docs/superpowers/specs/2026-09-13-conversation-persistence-design.md`, and the consolidated requirements in this plan's “Global Constraints” and “Release Gates”.

## Global Constraints

- Preserve the dependency direction documented in `AGENTS.md`; domain and math remain independent of Spring, MyBatis and transport code.
- Preserve `FinancialResearchWorkflow.run(...)` as the only new-run entry used by JSON and SSE APIs.
- Keep every business role as an AgentScope `ReActAgent`; do not reintroduce a custom reasoning/acting loop.
- Keep tool output as the source of truth for returns, drawdown, Sharpe, Calmar and other financial metrics.
- Derive `tenantId`, `userId`, `conversationId`, `workflowRunId` and `sessionKey` on the server. The LLM must never supply or override them.
- Allow Agent nodes inside one Run to execute concurrently; serialize Runs that belong to the same conversation.
- Allow different conversations owned by the same user to run concurrently subject to user quotas.
- Separate global market knowledge from user memory and conversation memory. User facts must never enter an unscoped Graphiti search.
- PostgreSQL is the source of truth for run state, leases, billing reservations and replayable events in clustered deployments.
- `copilot.redis.enabled=false` must remain valid for local and test single-instance operation. Redis accelerates coordination and event delivery but is not the only durable store.
- Shared Tool beans must be stateless and thread-safe. Invocation state travels through AgentScope `RuntimeContext` typed attributes.
- Assign one `workflowRunId` to the DAG and a distinct `agentRunId` to each Agent invocation. Never rely on `ThreadLocal` for identity.
- Enforce global, per-user, per-run and per-conversation admission limits. Return `429` for caller quota exhaustion and `503` for system capacity exhaustion.
- Preserve authenticated ownership checks and existing 404/503 behavior for unavailable or unauthorized conversation resources.
- Never persist chain-of-thought, complete large tool results, credentials, authorization headers or payment secrets.
- Do not commit, reset or discard unrelated working-tree changes. Commit steps are intentionally omitted until the user explicitly authorizes commits.

## Release Gates

1. **Gate A — Identity and memory isolation:** safe to deploy on one instance before scheduler changes.
2. **Gate B — Single-instance capacity:** global scheduler, model gateway, conversation serialization and overload responses are live.
3. **Gate C — Multi-instance durability:** PostgreSQL leases/events support worker takeover and SSE reconnect.
4. **Gate D — Billing and operations:** reservation/settlement and production metrics close the capacity loop.

Each gate must pass its focused tests and the full Maven suite before work proceeds to the next gate. Service-dependent integration tests remain opt-in.

---

### Task 1: Restore a trustworthy test baseline

**Files:**
- Modify: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/architecture/NoLegacyExecutionArchitectureTest.java`
- Modify: `copilot-data-engine/src/test/java/com/financial/copilot/data/rag/service/RagSchemaSyncServiceIntegrationTest.java`
- Modify: `copilot-data-engine/src/test/java/com/financial/copilot/data/rag/adapter/PostgresRagSchemaAdapterTest.java`

**Interfaces:**
- Produces a default unit-test suite that never contacts PostgreSQL, Neo4j, Redis or Ollama.
- Produces explicit `copilot.integration=true` gating for service-dependent tests.

- [ ] **Step 1: Update the architecture test fixture**

Point the source scan at the current package root and assert the old packages remain absent:

```java
Path sourceRoot = Path.of("src/main/java/com/financial/copilot/agent/core");
assertThat(sourceRoot).exists();
assertThat(findJavaSources(sourceRoot))
        .noneMatch(path -> path.toString().contains("agent/core/agents"));
```

- [ ] **Step 2: Gate live RAG tests**

Add an explicit property condition to every test that needs external services:

```java
@EnabledIfSystemProperty(named = "copilot.integration", matches = "true")
class RagSchemaSyncServiceIntegrationTest {
}
```

Keep adapter unit tests mocked; move live connectivity assertions to an `*IntegrationTest` class.

- [ ] **Step 3: Run the focused baseline**

Run:

```powershell
mvn -s maven-settings.xml -pl copilot-agent-core,copilot-data-engine test "-Dcopilot.neo4j.enabled=false"
```

Expected: PASS without network or local infrastructure.

---

### Task 2: Introduce immutable execution identity and conversation snapshots

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/context/ExecutionIdentity.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/context/ConversationContextSnapshot.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/runtime/GraphRunRequest.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/runtime/NodeExecutionContext.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/platform/conversation/ConversationService.java`
- Modify: `copilot-app/src/main/java/com/financial/copilot/controller/ResearchAgentController.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/context/ExecutionIdentityTest.java`
- Test: `copilot-app/src/test/java/com/financial/copilot/controller/ResearchAgentControllerTest.java`

**Interfaces:**
- Produces `ExecutionIdentity(String tenantId, Long userId, UUID conversationId, String workflowRunId, String sessionKey)`.
- Produces `ConversationContextSnapshot(List<String> completedMessages, UserInvestmentProfile profile, String currentPrompt, Instant capturedAt)`.
- `GraphRunRequest` consumes the two records and removes duplicated mutable identity fields.

- [ ] **Step 1: Write failing identity tests**

```java
@Test
void identityRejectsBlankServerOwnedKeys() {
    assertThatThrownBy(() -> new ExecutionIdentity("DEFAULT", 7L, UUID.randomUUID(), "", "session"))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void contextSnapshotDefensivelyCopiesMessages() {
    List<String> messages = new ArrayList<>(List.of("USER: first"));
    ConversationContextSnapshot snapshot = new ConversationContextSnapshot(
            messages, null, "next", Instant.now());
    messages.add("ASSISTANT: mutated");
    assertThat(snapshot.completedMessages()).containsExactly("USER: first");
}
```

- [ ] **Step 2: Implement the immutable records**

```java
public record ExecutionIdentity(
        String tenantId,
        Long userId,
        UUID conversationId,
        String workflowRunId,
        String sessionKey
) {
    public ExecutionIdentity {
        tenantId = requireText(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(conversationId, "conversationId");
        workflowRunId = requireText(workflowRunId, "workflowRunId");
        sessionKey = requireText(sessionKey, "sessionKey");
    }
}
```

Use `DEFAULT` only from server configuration until authenticated tenancy is added; never accept it from request JSON.

- [ ] **Step 3: Build and use one conversation snapshot**

Change `ConversationService.recentContext(...)` into a snapshot-producing operation and consume its return value in the controller:

```java
ConversationContextSnapshot snapshot = conversations.snapshot(
        uid, run.conversationId(), key, request.getPrompt(),
        userService.getInvestmentProfile(uid), 20);
ExecutionIdentity identity = new ExecutionIdentity(
        "DEFAULT", uid, run.conversationId(), run.runId().toString(), key);
```

Pass both records through `GraphRunRequest`; remove the current discarded `recentContext(...)` call.

- [ ] **Step 4: Verify identity propagation**

Run:

```powershell
mvn -s maven-settings.xml -pl copilot-agent-core,copilot-app -am -Dtest=ExecutionIdentityTest,ResearchAgentControllerTest test
```

Expected: the same immutable identity and snapshot reach Planner and node execution.

---

### Task 3: Enforce user-scoped memory namespaces end to end

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/memory/MemoryScope.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/memory/MemoryClient.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/planner/tool/MarketMemoryTool.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/planner/GraphPlannerAgent.java`
- Modify: `memory-service/app/models/schemas.py`
- Modify: `memory-service/app/api/sessions.py`
- Modify: `memory-service/app/api/memory.py`
- Modify: `memory-service/app/api/profile.py`
- Modify: `memory-service/app/engine/short_term.py`
- Modify: `memory-service/app/engine/long_term.py`
- Modify: `memory-service/app/engine/profile_engine.py`
- Create: `memory-service/app/api/security.py`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/dag/planner/tool/MarketMemoryToolTest.java`
- Test: `memory-service/tests/unit/test_memory_isolation.py`
- Test: `memory-service/tests/api/test_session_ownership.py`

**Interfaces:**
- Produces `MemoryScope.GLOBAL_MARKET`, `MemoryScope.USER`, and `MemoryScope.CONVERSATION`.
- Produces `MemoryClient.searchMemory(ExecutionIdentity identity, MemoryScope scope, String query, int limit)`.
- Produces owner-checked memory-service routes under `/v1/users/{user_id}/sessions/{conversation_id}`.

- [ ] **Step 1: Write failing Java tests that forbid model-supplied identity**

```java
@Test
void memoryToolUsesInjectedIdentityAndOnlyAcceptsQuery() {
    ExecutionIdentity identity = identity(7L, conversationId);
    tool.retrieveMemory(identity, "低回撤", 5);
    verify(memoryClient).searchMemory(identity, MemoryScope.USER, "低回撤", 5);
}
```

Change the AgentScope tool schema to expose only `query`; inject `NodeExecutionContext` as an unannotated parameter:

```java
@Tool(name = "read_memory", description = "读取当前用户的投研记忆", readOnly = true)
public Object memory(
        @ToolParam(name = "query", description = "检索词") String query,
        NodeExecutionContext context) {
    return memory.retrieveMemory(context.request().identity(), query, 5);
}
```

- [ ] **Step 2: Write failing Python ownership tests**

```python
async def test_user_cannot_read_another_users_session(client, session_store):
    await session_store.add_message("u1", "c1", "user", "private")
    response = await client.get("/v1/users/u2/sessions/c1/context")
    assert response.status_code == 404

async def test_user_search_passes_private_namespace(memory_engine):
    await memory_engine.search("u1", "USER", "低回撤", 5)
    assert memory_engine.last_namespace == "user:u1"
```

- [ ] **Step 3: Implement namespaced keys and graph searches**

Use explicit namespaces:

```python
def short_term_key(user_id: str, conversation_id: str) -> str:
    return f"memory:shortterm:user:{user_id}:conversation:{conversation_id}"
```

For Graphiti, write and search with the same server-derived group/namespace. `GLOBAL_MARKET` uses a global group; `USER` uses `user:{user_id}`; `CONVERSATION` uses `user:{user_id}:conversation:{conversation_id}`. Reject a request when the requested scope does not match the supplied owner.

- [ ] **Step 4: Add internal service authentication**

Require `X-Copilot-Service-Token` through a FastAPI dependency and configure the Java client from `MEMORY_SERVICE_TOKEN`. Return `401` for missing/invalid service credentials and `404` for owner mismatches.

- [ ] **Step 5: Run isolation tests**

Run:

```powershell
mvn -s maven-settings.xml -pl copilot-agent-core -am -Dtest=MarketMemoryToolTest test
Set-Location memory-service
python -m pytest tests/unit/test_memory_isolation.py tests/api/test_session_ownership.py -q
```

Expected: user facts never appear in another user's result; global market facts remain shareable.

---

### Task 4: Make AgentScope context and role toolkits explicit

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/agentscope/AgentInvocationIdentity.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/agentscope/RoleToolkitRegistry.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/agentscope/AgentScopeAgentFactory.java`
- Modify: every role under `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/agentscope/AgentScopeAgentFactoryTest.java`
- Create: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/agentscope/RoleToolkitRegistryTest.java`

**Interfaces:**
- Produces `AgentInvocationIdentity(String workflowRunId, String agentRunId, String nodeId, int attempt)`.
- Produces immutable role-specific toolkits; no global all-tools toolkit.
- Makes `NodeExecutionContext` and `AgentInvocationIdentity` available to tools through typed `RuntimeContext` injection.

- [ ] **Step 1: Write failing context-correlation tests**

```java
@Test
void runtimeContextCarriesDistinctAgentAndWorkflowRunIds() {
    RuntimeContext captured = invokeAndCaptureContext(nodeContext("wf-1", "analysis", 2));
    assertThat(captured.getRunId()).isEqualTo("wf-1:analysis:2");
    assertThat(captured.get("workflowRunId")).isEqualTo("wf-1");
    assertThat(captured.get(NodeExecutionContext.class).nodeId()).isEqualTo("analysis");
}
```

- [ ] **Step 2: Build RuntimeContext without ThreadLocal identity**

```java
String agentRunId = request.identity().workflowRunId()
        + ":" + context.nodeId() + ":" + context.attempt();
RuntimeContext runtimeContext = RuntimeContext.builder()
        .runId(agentRunId)
        .userId(String.valueOf(request.identity().userId()))
        .sessionId(request.identity().sessionKey())
        .put("workflowRunId", request.identity().workflowRunId())
        .put(NodeExecutionContext.class, context)
        .put(ExecutionIdentity.class, request.identity())
        .build();
```

Add `attempt` to `NodeExecutionContext`; construct a fresh RuntimeContext for every Agent call.

- [ ] **Step 3: Centralize static toolkit registration**

Create immutable role toolkits for stateless public tools. Move invocation-specific data such as `NodeInput` and identity into injected context instead of capturing it in shared objects. Do not mutate a shared `Toolkit` after application startup.

```java
public Toolkit toolkitFor(AgentRole role) {
    return switch (role) {
        case FUND_ANALYZER -> fundAnalyzerToolkit;
        case FUND_SCREENER -> fundScreenerToolkit;
        case REPORT_SYNTHESIZER -> reportToolkit;
        default -> throw new IllegalArgumentException("Unsupported role: " + role);
    };
}
```

- [ ] **Step 4: Verify concurrent context isolation**

Run two Agent calls with different user/session/workflow IDs against one shared role toolkit and assert each injected tool invocation observes its own context.

Run:

```powershell
mvn -s maven-settings.xml -pl copilot-agent-core -Dtest=AgentScopeAgentFactoryTest,RoleToolkitRegistryTest test
```

Expected: no context crosses calls and AgentScope `runId` is unique per node attempt.

---

### Task 5: Route every model call through one execution gateway

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/llm/execution/LlmExecutionGateway.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/llm/execution/LlmCallContext.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/llm/execution/LlmCapacityLimiter.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/agentscope/AgentScopeAgentFactory.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/config/DagRuntimeConfig.java`
- Modify: `copilot-app/src/main/resources/application.yml`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/llm/execution/LlmExecutionGatewayTest.java`

**Interfaces:**
- Produces `Flux<ChatResponse> execute(LlmCallContext context, Supplier<Flux<ChatResponse>> invocation)`.
- Applies capacity, timeout, usage metering and atomic billing callback to Planner, Replanner and business Agents alike.

- [ ] **Step 1: Write failing capacity and release tests**

```java
@Test
void plannerAndBusinessCallsShareTheSameLimit() {
    LlmCapacityLimiter limiter = new LlmCapacityLimiter(1);
    StepVerifier.create(Flux.merge(
            gateway.execute(context("planner"), slowCall()),
            gateway.execute(context("analysis"), slowCall())))
            .expectNextCount(2)
            .verifyComplete();
    assertThat(limiter.maxObservedConcurrency()).isEqualTo(1);
}

@Test
void cancellationAndErrorReleasePermit() {
    StepVerifier.create(gateway.execute(context("analysis"), Flux::error))
            .expectError()
            .verify();
    assertThat(limiter.availablePermits()).isEqualTo(1);
}
```

- [ ] **Step 2: Implement the gateway around the AgentScope Model**

Wrap `Model.stream(...)` with `Flux.usingWhen` or `doFinally` so permits are released for success, error and cancellation. Keep token usage and billing inside the gateway; remove duplicated model metering from individual roles.

- [ ] **Step 3: Add configuration properties**

```yaml
copilot:
  runtime:
    max-llm-concurrency: ${COPILOT_MAX_LLM_CONCURRENCY:16}
    llm-call-timeout: ${COPILOT_LLM_CALL_TIMEOUT:120s}
```

The server enforces these limits. Ignore LLM-generated node `resourceRequirements` for actual provider capacity.

- [ ] **Step 4: Verify every Agent path uses the gateway**

Add an architecture test that fails when `OpenAIChatModel` is constructed outside the model provider/gateway package.

Run:

```powershell
mvn -s maven-settings.xml -pl copilot-agent-core -Dtest=LlmExecutionGatewayTest,NoLegacyExecutionArchitectureTest test
```

---

### Task 6: Replace per-Run wakeups with a global fair ready scheduler

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/scheduler/ReadyNode.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/scheduler/GlobalReadyScheduler.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/scheduler/RunAdmissionController.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/scheduler/RuntimeCapacityProperties.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/scheduler/AdmissionDecision.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/runtime/DagRuntime.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/runtime/resource/ResourceManager.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/config/DagRuntimeConfig.java`
- Modify: `copilot-app/src/main/resources/application.yml`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/dag/scheduler/GlobalReadySchedulerTest.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/dag/scheduler/RunAdmissionControllerTest.java`

**Interfaces:**
- Produces one process-wide queue of `ReadyNode(tenantId, userId, runId, conversationId, nodeId, priority, readyAt, resources)`.
- Produces `AdmissionDecision.ADMITTED`, `QUEUED_USER_LIMIT`, `QUEUED_CONVERSATION`, `REJECTED_USER_LIMIT`, and `REJECTED_SYSTEM_CAPACITY`.
- Resource release signals the global scheduler, not an individual Run queue.

- [ ] **Step 1: Reproduce the cross-Run stall**

```java
@Test
void waitingRunWakesWhenAnotherRunReleasesTheOnlyPermit() throws Exception {
    ResourceManager resources = new ResourceManager(Map.of(ResourceType.AGENT, 1));
    GraphRunHandle first = runtime(resources).run(request("run-a", 1L), blockingGraph("a"));
    GraphRunHandle second = runtime(resources).run(request("run-b", 2L), graph("b"));
    releaseFirst.countDown();
    assertThat(second.completion().get(2, SECONDS).nodeStatuses().get("b"))
            .isEqualTo(NodeStatus.SUCCEEDED);
}
```

Verify this fails or times out with the current per-Run ready queues.

- [ ] **Step 2: Implement fair hierarchical selection**

Use round-robin selection by tenant, then user, then Run. Within a Run order nodes by effective priority:

```java
long effectivePriority(ReadyNode node, Instant now) {
    long ageSeconds = Duration.between(node.readyAt(), now).toSeconds();
    return node.priority().getWeight() * 1_000L + Math.min(ageSeconds, 999L);
}
```

Do not stop on a head node whose resource type is unavailable; scan other fair candidates that can acquire their declared resources.

- [ ] **Step 3: Add admission and conversation serialization**

Apply these defaults:

```yaml
copilot:
  runtime:
    max-active-runs: ${COPILOT_MAX_ACTIVE_RUNS:100}
    max-queued-runs: ${COPILOT_MAX_QUEUED_RUNS:1000}
    max-active-runs-per-user: ${COPILOT_MAX_ACTIVE_RUNS_PER_USER:2}
    max-agents-per-run: ${COPILOT_MAX_AGENTS_PER_RUN:3}
    max-agent-concurrency: ${COPILOT_MAX_AGENT_CONCURRENCY:32}
    max-rag-concurrency: ${COPILOT_MAX_RAG_CONCURRENCY:32}
    queue-timeout: ${COPILOT_QUEUE_TIMEOUT:5m}
```

Hold one active permit per `(tenantId, userId, conversationId)`. Queue a later turn and capture its conversation snapshot only when it becomes active so it includes the previous completed answer.

- [ ] **Step 4: Add fairness, starvation and cancellation tests**

Test at least:

```text
one user cannot occupy every Agent permit
a low-priority node eventually runs because of aging
different conversations for one user can run concurrently within the user limit
the second Run in one conversation waits for the first terminal state
cancelling a queued Run removes it and releases admission counts
queue timeout reaches a terminal state
```

- [ ] **Step 5: Run focused runtime tests**

```powershell
mvn -s maven-settings.xml -pl copilot-agent-core -Dtest=GlobalReadySchedulerTest,RunAdmissionControllerTest,DagRunIsolationTest,DagRuntimeTest test
```

Expected: no waiting Run remains stranded after another Run releases capacity.

---

### Task 7: Expose queueing and overload semantics through the unified API

**Files:**
- Modify: `copilot-common/src/main/java/com/financial/copilot/common/event/ResearchStreamEvent.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/event/NodeEventBus.java`
- Modify: `copilot-app/src/main/java/com/financial/copilot/controller/ResearchAgentController.java`
- Modify: `copilot-app/src/main/java/com/financial/copilot/controller/ResearchRunController.java`
- Create: `copilot-app/src/main/java/com/financial/copilot/exception/ResearchCapacityExceptionHandler.java`
- Test: `copilot-app/src/test/java/com/financial/copilot/controller/ResearchAgentControllerTest.java`
- Test: `copilot-app/src/test/java/com/financial/copilot/controller/ResearchRunControllerTest.java`

**Interfaces:**
- Produces SSE events `run_queued`, `run_admitted`, `run_started`, and existing terminal events.
- Produces `429` for per-user limits and `503` for system queue exhaustion.
- Keeps JSON and SSE on the same `FinancialResearchWorkflow.run(...)` path.

- [ ] **Step 1: Write failing controller tests**

```java
webTestClient.post().uri("/api/v1/research/runs")
        .contentType(APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus().isEqualTo(429);

webTestClient.post().uri("/api/v1/research/runs")
        .contentType(APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus().isEqualTo(503);
```

- [ ] **Step 2: Remove blocking JSON joins**

Replace `completion().join()` inside `boundedElastic` with direct reactive composition:

```java
return requireCurrentUserId(null)
        .flatMap(uid -> Mono.fromCallable(() -> start(uid, request, RunMode.SYNC))
                .subscribeOn(Schedulers.boundedElastic()))
        .flatMap(started -> Mono.fromFuture(started.completion())
                .map(result -> ApiResult.success(toResponse(started, result))));
```

- [ ] **Step 3: Emit queue lifecycle events**

Ensure a queued SSE request remains connected, emits its position or admission transition without leaking other users' queue data, and cancellation removes the queued Run.

- [ ] **Step 4: Verify API behavior**

```powershell
mvn -s maven-settings.xml -pl copilot-app -am -Dtest=ResearchAgentControllerTest,ResearchRunControllerTest test
```

---

### Task 8: Persist clustered Run state, node attempts, leases and replayable events

**Files:**
- Create: `copilot-domain/src/main/java/com/financial/copilot/domain/platform/execution/port/ResearchExecutionPort.java`
- Create: `copilot-domain/src/main/java/com/financial/copilot/domain/platform/execution/entity/ResearchRunState.java`
- Create: `copilot-domain/src/main/java/com/financial/copilot/domain/platform/execution/entity/NodeAttempt.java`
- Create: `copilot-domain/src/main/java/com/financial/copilot/domain/platform/execution/entity/ResearchRunEvent.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/execution/adapter/PostgresResearchExecutionAdapter.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/execution/mapper/ResearchRunStateMapper.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/execution/mapper/NodeAttemptMapper.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/execution/mapper/ResearchRunEventMapper.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/execution/po/ResearchRunStatePO.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/execution/po/NodeAttemptPO.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/execution/po/ResearchRunEventPO.java`
- Create: `copilot-app/src/main/resources/db/execution-schema.sql`
- Modify: `copilot-app/src/main/resources/application.yml`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/runtime/GraphRunRegistry.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/runtime/DagRuntime.java`
- Modify: `copilot-app/src/main/java/com/financial/copilot/controller/ResearchRunController.java`
- Test: `copilot-data-engine/src/test/java/com/financial/copilot/data/execution/PostgresResearchExecutionAdapterTest.java`
- Test: `copilot-app/src/test/java/com/financial/copilot/ResearchExecutionPersistenceTest.java`

**Interfaces:**
- Produces durable keys `(user_id, run_id)` and idempotent node attempt keys `(run_id, node_id, attempt)`.
- Produces lease operations `claimReadyNodes(workerId, limit, leaseUntil)`, `renewLease(...)`, `completeAttempt(...)`, and `releaseExpiredLeases(...)`.
- Produces ordered per-Run events with monotonically increasing `sequence_no` for `Last-Event-ID` replay.

- [ ] **Step 1: Add DDL with owner, idempotency and lease constraints**

```sql
CREATE TABLE research_run_state (
    run_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    graph_snapshot JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE research_node_attempt (
    run_id UUID NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    attempt INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    worker_id VARCHAR(128),
    lease_until TIMESTAMPTZ,
    artifact JSONB,
    PRIMARY KEY (run_id, node_id, attempt)
);

CREATE TABLE research_run_event (
    run_id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, sequence_no)
);
```

- [ ] **Step 2: Claim work with PostgreSQL leases**

Use one transaction and `FOR UPDATE SKIP LOCKED` to claim ready attempts. A worker renews its lease while running; another worker may reclaim only after `lease_until` expires. Completion uses a conditional update on `worker_id`, `attempt` and RUNNING status.

- [ ] **Step 3: Make GraphRunRegistry a local cache**

Keep fast local cancellation handles, but resolve Run ownership and status through `ResearchExecutionPort`. Write cancellation intent to PostgreSQL first; Redis pub/sub may notify the owning process when enabled.

- [ ] **Step 4: Add SSE replay**

Accept `Last-Event-ID`, query events with `sequence_no > lastSeen`, then subscribe to live local/Redis delivery. Always filter by authenticated `userId + runId`.

- [ ] **Step 5: Verify takeover and replay**

Test two logical workers:

```text
worker A claims node attempt 1
worker A stops renewing
lease expires
worker B claims the same attempt identity
only worker B can complete it
SSE reconnect replays each persisted event once in sequence order
```

Run the adapter unit test by default. Run the PostgreSQL integration test only when the service is available:

```powershell
mvn -s maven-settings.xml -pl copilot-data-engine -Dtest=PostgresResearchExecutionAdapterTest test
mvn -s maven-settings.xml -pl copilot-app -Dcopilot.integration=true -Dtest=ResearchExecutionPersistenceTest test
```

---

### Task 9: Add wallet reservation and idempotent settlement

**Files:**
- Modify: `copilot-domain/src/main/java/com/financial/copilot/domain/platform/billing/port/BillingPort.java`
- Create: `copilot-domain/src/main/java/com/financial/copilot/domain/platform/billing/entity/PointReservation.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/platform/billing/WalletBillingService.java`
- Modify: `copilot-data-engine/src/main/java/com/financial/copilot/data/billing/mapper/UserWalletMapper.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/billing/mapper/PointReservationMapper.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/billing/po/PointReservationPO.java`
- Modify: `copilot-app/src/main/resources/db/billing-schema.sql`
- Modify: `copilot-app/src/main/java/com/financial/copilot/controller/ResearchAgentController.java`
- Modify: `copilot-app/src/main/java/com/financial/copilot/controller/ResearchRunLifecycle.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/platform/billing/WalletBillingServiceTest.java`
- Test: `copilot-app/src/test/java/com/financial/copilot/BillingPersistenceTest.java`

**Interfaces:**
- Produces `reserve(userId, workflowRunId, estimatedPoints)`, `settle(userId, workflowRunId, actualPoints)`, and `release(userId, workflowRunId)`.
- Makes every operation idempotent by unique `workflow_run_id`.

- [ ] **Step 1: Write failing concurrent reservation tests**

```java
@Test
void concurrentRunsCannotReserveMoreThanAvailableBalance() {
    PointReservation first = billing.reserve(7L, "run-a", 800L);
    assertThatThrownBy(() -> billing.reserve(7L, "run-b", 800L))
            .isInstanceOf(WalletInsufficientException.class);
    assertThat(first.status()).isEqualTo(ReservationStatus.ACTIVE);
}

@Test
void duplicateSettlementDoesNotDoubleCharge() {
    billing.reserve(7L, "run-a", 500L);
    billing.settle(7L, "run-a", 420L);
    billing.settle(7L, "run-a", 420L);
    assertThat(wallet().totalConsumedPoints()).isEqualTo(420L);
}
```

- [ ] **Step 2: Add reservation DDL and atomic SQL**

Create a unique reservation row per Run. Reserve by atomically increasing `frozen_points` only when `balance_points - frozen_points >= estimate`. Settle in one transaction by decreasing frozen points, deducting actual points and marking the reservation SETTLED. Release unused reservations on failure, cancellation and queue timeout.

- [ ] **Step 3: Integrate with Run lifecycle**

Reserve before admission, settle token usage idempotently through `LlmExecutionGateway`, and release the unused remainder in `ResearchRunLifecycle` terminal handling.

- [ ] **Step 4: Verify billing concurrency**

```powershell
mvn -s maven-settings.xml -pl copilot-agent-core,copilot-app -am -Dtest=WalletBillingServiceTest,BillingPersistenceTest test
```

Expected: concurrent Runs cannot overspend and retries cannot double-charge.

---

### Task 10: Add capacity metrics, health details and load verification

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/observability/RuntimeMetrics.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/scheduler/GlobalReadyScheduler.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/llm/execution/LlmExecutionGateway.java`
- Modify: `copilot-app/src/main/java/com/financial/copilot/controller/ResearchAgentController.java`
- Modify: `copilot-app/src/main/resources/application.yml`
- Create: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/observability/RuntimeMetricsTest.java`
- Create: `scripts/load/research-concurrency.js`
- Create: `docs/operations/research-runtime-capacity.md`

**Interfaces:**
- Produces gauges/counters for active and queued Runs, queue wait, Agent/LLM/RAG permits, node latency, queue timeout, cancellation, provider errors and lease takeovers.
- Produces a reproducible 100-Run isolation/capacity load scenario.

- [ ] **Step 1: Register bounded-cardinality metrics**

Use metric names without `userId`, `conversationId` or `runId` tags:

```text
copilot.runtime.runs.active
copilot.runtime.runs.queued
copilot.runtime.queue.wait
copilot.runtime.nodes.duration
copilot.runtime.nodes.cancelled
copilot.runtime.llm.active
copilot.runtime.llm.errors
copilot.runtime.leases.takeover
```

- [ ] **Step 2: Add deterministic load assertions**

The load script must submit 100 Runs across at least 20 users and assert:

```text
no response contains another user's marker
active Agents never exceed configured capacity
one conversation never has two active Runs
429 and 503 responses match configured thresholds
queued Runs eventually start after capacity is released
SSE reconnect receives ordered, non-duplicated event IDs
```

- [ ] **Step 3: Document capacity tuning**

Document how to size Agent, LLM, RAG and JDBC capacities together, how local Redis-disabled mode differs from clustered mode, and which alerts fire on queue wait, provider errors, database pool saturation and lease takeover.

- [ ] **Step 4: Run final verification**

Run focused tests first, then:

```powershell
mvn -s maven-settings.xml test "-Dcopilot.neo4j.enabled=false"
```

Expected: PASS without contacting service-dependent integrations.

If PostgreSQL, Redis, Neo4j, Ollama and memory-service are all available, additionally run:

```powershell
mvn -s maven-settings.xml test "-Dcopilot.integration=true"
Set-Location memory-service
python -m pytest -q
```

Expected: PASS, followed by the documented load test within configured concurrency ceilings.

## Implementation Order Summary

```text
Task 1  Test baseline
  ↓
Task 2  Execution identity and immutable conversation snapshot
  ↓
Task 3  Memory namespaces and service ownership
  ↓ Gate A
Task 4  AgentScope context and role Toolkit registry
  ↓
Task 5  Unified LLM gateway
  ↓
Task 6  Global fair scheduler and admission
  ↓
Task 7  Queue-aware API semantics
  ↓ Gate B
Task 8  PostgreSQL leases and replayable events
  ↓ Gate C
Task 9  Billing reservation and settlement
  ↓
Task 10 Metrics, operations and load verification
  ↓ Gate D
Production readiness
```

## Plan Self-Review

- **Requirement coverage:** single-instance concurrency, resource configuration, unified model calls, user fairness, overload responses, multi-instance leases, Redis-optional operation, billing reservation, memory isolation, immutable context snapshots, same-conversation serialization, service authentication, Toolkit reuse, RuntimeContext injection and run ID correlation each map to a numbered task.
- **Dependency check:** identity precedes memory and AgentScope context; the model gateway precedes scheduler admission; the single-instance scheduler precedes distributed leases; durable Run identity precedes billing settlement and replayable events.
- **Type consistency:** `ExecutionIdentity`, `ConversationContextSnapshot`, `AgentInvocationIdentity`, `MemoryScope`, `ReadyNode`, `AdmissionDecision` and `LlmCallContext` are introduced before their consumers.
- **Scope control:** Reflection, Router-first dispatch and NER integration are intentionally excluded. They belong to a separate behavior plan after concurrency and isolation foundations are complete.
- **Working-tree safety:** this plan adds only this Markdown file now; implementation must preserve all existing unrelated stock, RAG, Docker and `ner-service` changes.
