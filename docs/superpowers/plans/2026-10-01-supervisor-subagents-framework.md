# Supervisor/Subagents Framework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the experimental branch's custom DAG/AgentScope orchestration with a Spring AI Alibaba `ReactAgent` supervisor that delegates to isolated financial subagents through official `AgentTool` callbacks.

**Architecture:** Keep `FinancialResearchWorkflow.run(...)` as the single application entry and retain the existing domain, math, data, conversation, billing, and deterministic tool layers. Spring AI Alibaba owns ReAct loops, subagent delegation, and graph state; FinancialCopilot owns authenticated identity, immutable conversation snapshots, admission control, model governance, evidence, events, and API semantics.

**Tech Stack:** Java 21, Spring Boot 3.5.8, Spring AI 1.1.2, Spring AI Alibaba 1.1.2.2, Reactor, PostgreSQL, optional Redis, JUnit 5, Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-01-supervisor-subagents-framework-design.md`

## Global Constraints

- Use Spring AI Alibaba 1.1.2.2's official `ReactAgent` plus `AgentTool.getFunctionToolCallback(...)` supervisor pattern.
- Preserve `FinancialResearchWorkflow.run(...)` as the only new-run entry used by JSON and SSE controllers.
- Keep financial calculations in deterministic tools and `copilot-math-core`; model prose is never a quantitative source of truth.
- Read the authenticated user once at the HTTP boundary and propagate identity explicitly through request, graph config, and hidden tool context.
- Never expose `tenantId`, `userId`, `conversationId`, `workflowRunId`, `agentRunId`, `sessionKey`, or `assistantMessageId` in model-visible tool schemas.
- Serialize runs for the same conversation; allow different conversations to run concurrently within user and global limits.
- Shared agents and tool beans must be stateless after initialization.
- Use a single-writer Run state machine; subagents publish append-only evidence and never mutate shared Run or Conversation state.
- Deduplicate every agent/tool operation with a server-generated idempotency key and merge evidence with revision-based compare-and-set.
- Use one `workflowRunId` per request and one new `agentRunId` per supervisor/subagent invocation.
- Do not use `ThreadLocal` for identity.
- Preserve 404 semantics for unknown or unauthorized conversations/runs, 429 for caller capacity, and 503 for system capacity.
- Do not persist chain-of-thought or unsanitized large tool payloads.
- Do not commit, reset, or discard changes unless the user explicitly requests it; commit steps are omitted intentionally.

---

### Task 1: Prove framework dependency compatibility

**Files:**
- Modify: `pom.xml`
- Modify: `copilot-agent-core/pom.xml`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/supervisor/SpringAiAlibabaCompatibilityTest.java`

**Interfaces:**
- Consumes: the current Java 21 Maven reactor.
- Produces: resolvable Spring AI `ChatModel`, Spring AI Alibaba `ReactAgent`, `AgentTool`, and `MemorySaver` APIs.

- [ ] **Step 1: Add a compile-time compatibility test**

```java
class SpringAiAlibabaCompatibilityTest {
    @Test
    void exposesSupervisorBuildingBlocks() {
        assertThat(ReactAgent.class).isNotNull();
        assertThat(AgentTool.class).isNotNull();
        assertThat(new MemorySaver()).isNotNull();
    }
}
```

- [ ] **Step 2: Run the focused test and record the expected dependency failure**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core -am -Dtest=SpringAiAlibabaCompatibilityTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: test compilation fails because Spring AI Alibaba classes are not yet on the classpath.

- [ ] **Step 3: Pin compatible BOMs and dependencies**

Set root properties to Spring Boot `3.5.8`, Spring AI `1.1.2`, and Spring AI Alibaba `1.1.2.2`. Import `spring-ai-bom` and `spring-ai-alibaba-bom`. Add `spring-ai-alibaba-agent-framework` and the Spring AI OpenAI model module to `copilot-agent-core`. Remove the AgentScope dependency after no production source requires it in Task 9.

- [ ] **Step 4: Run the compatibility test**

Run the Step 2 command.

Expected: PASS and the reactor compiles on Java 21.

### Task 2: Introduce framework-independent run contracts and explicit identity

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/runtime/ExecutionIdentity.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/runtime/ConversationContextSnapshot.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/runtime/ResearchExecutionContext.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/runtime/ResearchRunRequest.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/runtime/ResearchRunResult.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/runtime/ResearchRunHandle.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/supervisor/runtime/ResearchExecutionContextTest.java`

**Interfaces:**
- Produces: immutable request, identity, snapshot, result, and handle types used by all later tasks.

- [ ] **Step 1: Write validation and thread-key tests**

```java
@Test
void threadKeyContainsEveryIsolationBoundary() {
    ExecutionIdentity identity = new ExecutionIdentity(7L, 11L, conversationId);
    ResearchExecutionContext context = ResearchExecutionContext.start(
            identity, "run-1", "agent-1", snapshot);
    assertThat(context.threadKey()).isEqualTo("7:11:" + conversationId + ":run-1");
}

@Test
void rejectsBlankWorkflowRunId() {
    assertThatThrownBy(() -> ResearchExecutionContext.start(identity, " ", "agent-1", snapshot))
            .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Verify RED**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core -am -Dtest=ResearchExecutionContextTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the runtime contracts do not exist.

- [ ] **Step 3: Implement immutable records**

Use compact constructors with `Objects.requireNonNull`, defensive `List.copyOf`/`Map.copyOf`, and blank identifier checks. `ResearchExecutionContext.child(AgentRole role)` returns a copy with a new UUID `agentRunId` and the same identity, workflow id, snapshot, and start time.

- [ ] **Step 4: Verify GREEN**

Run the Step 2 command and expect PASS.

### Task 3: Add conversation admission and immutable snapshot creation

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/admission/RuntimeCapacityProperties.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/admission/ConversationAdmissionController.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/admission/RunAdmission.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/context/ConversationContextSnapshotFactory.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/supervisor/admission/ConversationAdmissionControllerTest.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/supervisor/context/ConversationContextSnapshotFactoryTest.java`

**Interfaces:**
- Consumes: `ExecutionIdentity`, `ConversationService`.
- Produces: `RunAdmission acquire(ExecutionIdentity)` and `ConversationContextSnapshot create(ExecutionIdentity, String)`.

- [ ] **Step 1: Write concurrency tests**

Test that the first run for a conversation acquires a permit, a simultaneous second run receives `ConversationBusyException`, another conversation for the same user acquires until the per-user limit, closing `RunAdmission` releases all counters, and a saturated global limit raises `SystemCapacityExceededException`.

- [ ] **Step 2: Verify RED**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core -am -Dtest=ConversationAdmissionControllerTest,ConversationContextSnapshotFactoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement bounded counters and snapshot factory**

Use a lock-protected map keyed by `ExecutionIdentity.conversationId()` plus user/global counters. `RunAdmission` is idempotent `AutoCloseable`. The snapshot factory first invokes the existing ownership-checked conversation service, copies only completed messages, attaches the investment profile and current question, and returns no mutable collections.

- [ ] **Step 4: Verify GREEN**

Run the Step 2 command and expect PASS.

### Task 4: Govern every framework model call

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/llm/LlmExecutionGateway.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/llm/GovernedChatModel.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/llm/ChatModelDelegateFactory.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/supervisor/llm/GovernedChatModelTest.java`

**Interfaces:**
- Consumes: Spring AI `ChatModel`, `LlmConfigManager`, billing callback, `ResearchExecutionContext` from model options/context.
- Produces: one Spring `ChatModel` bean used by supervisor and every subagent.

- [ ] **Step 1: Write gateway behavior tests**

Use a recording delegate to prove one permit is acquired and released per `call`, usage is recorded with `workflowRunId` and `agentRunId`, failures release permits, and missing execution context is rejected before a provider call.

- [ ] **Step 2: Verify RED**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core -am -Dtest=GovernedChatModelTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement the decorator and delegate cache**

`GovernedChatModel` implements Spring AI `ChatModel` and delegates both synchronous and streaming calls through `LlmExecutionGateway`. `ChatModelDelegateFactory` caches provider clients by a non-secret configuration fingerprint and never logs API keys.

- [ ] **Step 4: Verify GREEN**

Run the Step 2 command and expect PASS.

### Task 5: Adapt deterministic financial tools to hidden framework context

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/tool/ResearchToolContext.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/tool/fund/FundResearchTools.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/tool/stock/StockResearchTools.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/tool/market/MarketResearchTools.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/evidence/AgentEvidence.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/evidence/RunEvidenceStore.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/supervisor/tool/FinancialToolSchemaIsolationTest.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/supervisor/evidence/RunEvidenceStoreIsolationTest.java`

**Interfaces:**
- Consumes: existing stateless `copilot-agent-tools` beans and hidden Spring AI `ToolContext`.
- Produces: role-specific `@Tool` methods and run-scoped evidence references.

- [ ] **Step 1: Write schema and isolation tests**

Generate Spring AI tool definitions and assert that no input schema contains `userId`, `conversationId`, `workflowRunId`, `agentRunId`, or `sessionKey`. Run 100 concurrent writes across distinct workflow ids and assert every snapshot contains only its own evidence.

- [ ] **Step 2: Verify RED**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core -am -Dtest=FinancialToolSchemaIsolationTest,RunEvidenceStoreIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement thin role adapters and evidence store**

Each adapter validates model-visible business input, extracts `ResearchExecutionContext` only from hidden `ToolContext`, invokes the existing deterministic tool, stores a sanitized evidence item, and returns a compact structured result. Use `ConcurrentHashMap<String, RunEvidence>` keyed by workflow id with immutable snapshots.

- [ ] **Step 4: Verify GREEN**

Run the Step 2 command and expect PASS.

### Task 6: Enforce idempotent operations and deterministic evidence merging

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/coordination/AgentOperation.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/coordination/OperationStatus.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/coordination/AgentOperationLedger.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/coordination/InMemoryAgentOperationLedger.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/coordination/EvidenceMergeCoordinator.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/coordination/RunStateMachine.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/supervisor/coordination/AgentOperationLedgerConcurrencyTest.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/supervisor/coordination/EvidenceMergeCoordinatorTest.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/supervisor/coordination/RunStateMachineTest.java`

**Interfaces:**
- Consumes: `ResearchExecutionContext`, `AgentEvidence`, `RunEvidenceStore`.
- Produces: `executeOnce(OperationRequest, Supplier<T>)`, revisioned evidence snapshots, merge barriers, and legal Run state transitions.

- [ ] **Step 1: Write duplicate-operation concurrency tests**

Start 100 concurrent callers with the same server-generated idempotency key. Assert the supplied tool function executes exactly once, all callers observe the same successful result reference, and only one ledger row exists. Add a second test proving different canonical inputs execute independently.

- [ ] **Step 2: Write stale-write and deterministic-merge tests**

Read revision 1, append new evidence to create revision 2, then assert a merge against revision 1 raises `StaleEvidenceRevisionException`. Supply the same evidence in different completion orders and assert the merged order is always `role`, `agentRunId`, `evidenceId`. Conflicting facts must both remain in the snapshot.

- [ ] **Step 3: Write state-machine and late-result tests**

Assert terminal states cannot transition back to `RUNNING`. Cancel a Run, deliver a late successful Agent result, and assert the result is audit-visible while the Run remains `CANCELLED` and its final report is unchanged.

- [ ] **Step 4: Verify RED**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core -am "-Dtest=AgentOperationLedgerConcurrencyTest,EvidenceMergeCoordinatorTest,RunStateMachineTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: FAIL because the coordination types do not exist.

- [ ] **Step 5: Implement the operation ledger**

Generate the idempotency key from `workflowRunId`, role, tool name, and SHA-256 of canonical JSON input. Use one atomic map entry per key and one completion future shared by all duplicate callers. Permit retries only for operations declared read-only and idempotent.

- [ ] **Step 6: Implement versioned merge and state transitions**

Keep Evidence append-only. Increment revision atomically after every accepted append. Compare the caller's expected revision before publishing a merged snapshot. Sort immutable evidence snapshots deterministically and preserve conflicts for Risk Review. Encode allowed state transitions in one `RunStateMachine` method.

- [ ] **Step 7: Verify GREEN**

Run the Step 4 command and expect PASS.

### Task 7: Build the official Supervisor/Subagents topology

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/agent/AgentRole.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/agent/SupervisorAgentConfiguration.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/agent/ContextualAgentToolCallback.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/agent/ResearchSupervisor.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/supervisor/agent/ResearchSupervisorTest.java`

**Interfaces:**
- Consumes: `GovernedChatModel`, role tool adapters, `ResearchExecutionContext`.
- Produces: singleton role `ReactAgent` beans, the supervisor `ReactAgent`, and `Mono<SupervisorResult> execute(...)`.

- [ ] **Step 1: Write scripted model delegation tests**

Script Spring AI responses for: a fund-only request calling only `fund_research`; a mixed request calling `fund_research` and `market_research`; and a response that must call `risk_review` before final output. Assert emitted agent events and final evidence references.

- [ ] **Step 2: Verify RED**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core -am -Dtest=ResearchSupervisorTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Configure immutable agents**

Create five role `ReactAgent` beans with only their permitted method tools. Wrap each using official `AgentTool.getFunctionToolCallback(...)`, then create the supervisor `ReactAgent` with those callbacks. `ContextualAgentToolCallback` adds hidden invocation context and a new child `agentRunId` without changing the model-visible schema.

- [ ] **Step 4: Implement the supervisor facade**

`ResearchSupervisor.execute(context, prompt)` constructs a framework thread id from `context.threadKey()`, streams framework events through the platform bridge, and returns only the final assistant text plus evidence references. Every AgentTool invocation first enters `AgentOperationLedger`; parallel branches join through `EvidenceMergeCoordinator` before Risk Review.

- [ ] **Step 5: Verify GREEN**

Run the Step 2 command and expect PASS.

### Task 8: Replace the workflow runtime and preserve JSON/SSE behavior

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/event/FrameworkEventBridge.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/runtime/SupervisorRunRegistry.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/workflow/FinancialResearchWorkflow.java`
- Modify: `copilot-app/src/main/java/com/financial/copilot/controller/ResearchAgentController.java`
- Modify: `copilot-app/src/main/java/com/financial/copilot/controller/ResearchRunController.java`
- Modify: `copilot-app/src/main/java/com/financial/copilot/controller/ResearchRunLifecycle.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/workflow/FinancialResearchWorkflowSupervisorTest.java`
- Test: `copilot-app/src/test/java/com/financial/copilot/controller/ResearchAgentControllerTest.java`

**Interfaces:**
- Consumes: admission, snapshot factory, supervisor, evidence store.
- Produces: `ResearchRunHandle run(ResearchRunRequest)`, user-scoped find/cancel, stable `ResearchStreamEvent` output.

- [ ] **Step 1: Write workflow and controller contract tests**

Prove JSON and SSE both invoke the same workflow method, admission is released on success/error/cancel, run lookup is user scoped, queued/busy/capacity failures map to 409/429/503 as configured, and final conversation persistence stores only the final report and sanitized metadata.

- [ ] **Step 2: Verify RED**

Run: `mvn -s maven-settings.xml -pl copilot-app -am -Dtest=FinancialResearchWorkflowSupervisorTest,ResearchAgentControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement run registry, event bridge, and workflow**

Create one unicast event sink and one completion future per run. Register by `(userId, workflowRunId)`, execute Supervisor on the configured executor, map public events, and always close admission in `whenComplete`. The workflow never constructs an execution graph.

- [ ] **Step 4: Update lifecycle and controllers**

Replace Graph-specific result extraction with `ResearchRunResult.finalReport()`. Status returns stable run status, sequence, start/completion times, and agent summaries. Remove resume because an in-memory Supervisor call cannot safely resume old DAG checkpoints; return 404 for nonexistent runs and 503 when durable resume is unavailable.

- [ ] **Step 5: Verify GREEN**

Run the Step 2 command and expect PASS.

### Task 9: Hard-delete legacy orchestration

**Files:**
- Delete: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/**`
- Delete: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/agentscope/**`
- Delete: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/**`
- Delete: corresponding legacy tests under `copilot-agent-core/src/test/java`
- Modify: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/architecture/NoLegacyExecutionArchitectureTest.java`
- Modify: `pom.xml`
- Modify: `copilot-agent-core/pom.xml`

**Interfaces:**
- Consumes: completed Supervisor workflow from Task 8.
- Produces: one production orchestration stack with no AgentScope or custom DAG references.

- [ ] **Step 1: Strengthen the architecture test before deletion**

Assert that production source contains none of `GraphPlannerAgent`, `AgentNodeRouter`, `DagRuntime`, `ExecutionGraph`, `GraphPatch`, `AgentScopeAgentFactory`, or imports starting with `io.agentscope`.

- [ ] **Step 2: Verify RED**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core -am -Dtest=NoLegacyExecutionArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL and list the remaining legacy production files.

- [ ] **Step 3: Delete legacy source and remove AgentScope dependencies**

Delete only orchestration-owned packages. Move any still-needed generic event, prompt sanitization, billing, conversation, security, and skill code out before removing directories.

- [ ] **Step 4: Verify GREEN**

Run the Step 2 command and expect PASS.

### Task 10: Configuration, documentation, and complete verification

**Files:**
- Modify: `copilot-app/src/main/resources/application.yml`
- Modify: `README.md`
- Create: `docs/supervisor-subagents-runtime.md`
- Test: all affected module tests.

**Interfaces:**
- Produces: documented local defaults, production capacity knobs, and repeatable verification.

- [ ] **Step 1: Add configuration binding tests**

Verify defaults for maximum active runs, per-user runs, agents per run, LLM concurrency, queue timeout, `parallel-tool-calls`, conversation persistence, and Redis disabled mode.

- [ ] **Step 2: Add architecture and operation documentation**

Document role boundaries, sequence diagram, identity propagation, local launch, model configuration, Redis-off behavior, event types, 404/409/429/503 semantics, and the absence of legacy DAG resume.

- [ ] **Step 3: Run focused module suites**

```powershell
mvn -s maven-settings.xml -pl copilot-agent-core -am test
mvn -s maven-settings.xml -pl copilot-app -am test
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Run the full non-service-dependent suite**

```powershell
mvn -s maven-settings.xml test "-Dcopilot.neo4j.enabled=false"
```

Expected: BUILD SUCCESS. Do not run `-Dcopilot.integration=true` unless PostgreSQL and Redis are available.

- [ ] **Step 5: Inspect the final diff**

Run `git diff --check`, `git status --short`, and `rg -n "GraphPlannerAgent|AgentNodeRouter|DagRuntime|AgentScopeAgentFactory|io\.agentscope" copilot-agent-core/src/main copilot-app/src/main`.

Expected: no whitespace errors and no legacy production references.
