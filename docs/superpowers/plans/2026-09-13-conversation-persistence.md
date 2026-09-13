# Durable Conversation History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Persist every user-visible research conversation and AgentScope tool execution in PostgreSQL while keeping Redis memory and DAG checkpoints as reconstructable, short-lived projections.

**Architecture:** A conversation domain port owns atomic message sequencing and user-scoped queries; a PostgreSQL adapter implements it with MyBatis and explicit transactions. ConversationService controls terminal states and cache projection. GraphRunRequest carries conversation identity through planning, execution, checkpoints and resume, while AgentScopeAgentFactory emits sanitized audit events. JSON and SSE continue through FinancialResearchWorkflow.run.

**Tech Stack:** Java 21, Spring Boot 3.3.3, WebFlux, AgentScope Java 2.0.0, MyBatis-Plus 3.5.7, PostgreSQL 16 JSONB, Redis, JUnit 5, Mockito, AssertJ.

**Spec:** docs/superpowers/specs/2026-09-13-conversation-persistence-design.md

## Global Constraints

- PostgreSQL is the source of truth for conversations, messages and tool audits.
- copilot.conversation.persistence-enabled defaults to true and disables only conversation, message and tool-audit database access when false.
- userId remains Long in Java and BIGINT in PostgreSQL and comes only from authentication.
- Redis memory has a 30-minute TTL and is rebuilt from PostgreSQL; DAG checkpoints keep their 24-hour recovery role.
- Never persist hidden reasoning, system prompts, credentials, headers or complete large tool results.
- Persist one USER and one RUNNING ASSISTANT message before Graph planning.
- Only RUNNING assistants enter COMPLETED, FAILED or CANCELLED; resume reuses the same runId/message.
- JSON and SSE share one lifecycle and FinancialResearchWorkflow.run remains the only execution entry.
- Do not retain a sessionId request alias or the old memory/session endpoint.
- All business agents remain AgentScope ReActAgent instances created by AgentScopeAgentFactory.

## File Structure

Domain files live under copilot-domain/src/main/java/com/financial/copilot/domain/conversation: entity records ResearchConversation, ConversationMessage, ConversationRun and AgentToolAudit; model enums ConversationStatus, MessageRole, MessageStatus and ToolAuditStatus; CursorPage; ConversationNotFoundException; and ports ConversationPort and AgentToolAuditPort.

PostgreSQL files live under copilot-data-engine/src/main/java/com/financial/copilot/data/conversation: three PO classes, three Mapper interfaces and PostgresConversationAdapter. DDL lives in copilot-app/src/main/resources/db/conversation-schema.sql.

Core files are ConversationService, ConversationPersistenceProperties, ConversationPersistenceDisabledException, ToolArgumentSanitizer and PersistentAgentToolAuditSink under agent/core/conversation; AgentToolAuditSink and ToolAuditEvent under agent/core/agentscope; plus changes to AgentScopeAgentFactory, GraphRunRequest, NodeExecutionContext, DagCheckpoint, DagRuntime, GraphPlanningRequest, GraphPlannerAgent, MarketMemoryTool and FinancialResearchWorkflow.

SSE identity propagation also modifies copilot-common/src/main/java/com/financial/copilot/common/event/ResearchStreamEvent.java and copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/event/NodeEventBus.java.

HTTP files are ResearchAgentController, ResearchRunController and a new ConversationController. Verification uses controller unit tests and copilot-app/src/test/java/com/financial/copilot/ConversationPersistenceTest.java.

---

### Task 1: Define conversation domain contracts

**Files:**
- Create: copilot-domain/src/main/java/com/financial/copilot/domain/conversation/entity/ResearchConversation.java
- Create: copilot-domain/src/main/java/com/financial/copilot/domain/conversation/entity/ConversationMessage.java
- Create: copilot-domain/src/main/java/com/financial/copilot/domain/conversation/entity/ConversationRun.java
- Create: copilot-domain/src/main/java/com/financial/copilot/domain/conversation/entity/AgentToolAudit.java
- Create: copilot-domain/src/main/java/com/financial/copilot/domain/conversation/model/ConversationStatus.java
- Create: copilot-domain/src/main/java/com/financial/copilot/domain/conversation/model/MessageRole.java
- Create: copilot-domain/src/main/java/com/financial/copilot/domain/conversation/model/MessageStatus.java
- Create: copilot-domain/src/main/java/com/financial/copilot/domain/conversation/model/ToolAuditStatus.java
- Create: copilot-domain/src/main/java/com/financial/copilot/domain/conversation/model/CursorPage.java
- Create: copilot-domain/src/main/java/com/financial/copilot/domain/conversation/exception/ConversationNotFoundException.java
- Create: copilot-domain/src/main/java/com/financial/copilot/domain/conversation/port/ConversationPort.java
- Create: copilot-domain/src/main/java/com/financial/copilot/domain/conversation/port/AgentToolAuditPort.java
- Test: copilot-domain/src/test/java/com/financial/copilot/domain/conversation/ConversationStateTest.java

**Interfaces:**
- Produces ConversationRun(UUID conversationId, UUID runId, Long userMessageId, Long assistantMessageId, MessageStatus assistantStatus); message IDs are null only when persistence is disabled.
- Produces owner-scoped ConversationPort and AgentToolAuditPort.

- [ ] **Step 1: Write failing state tests**

~~~java
@Test
void onlyRunningAssistantCanEnterATerminalState() {
    ConversationMessage running = ConversationMessage.runningAssistant(
            12L, UUID.randomUUID(), 7L, UUID.randomUUID(), 2L, Instant.now());
    ConversationMessage completed = running.complete("report", Map.of(), Instant.now());
    assertThat(completed.status()).isEqualTo(MessageStatus.COMPLETED);
    assertThatThrownBy(() -> completed.fail("ERROR", "failed", Instant.now()))
            .isInstanceOf(IllegalStateException.class);
}

@Test
void userMessageStartsCompleted() {
    ConversationMessage message = ConversationMessage.user(
            11L, UUID.randomUUID(), 7L, UUID.randomUUID(), 1L, "prompt", Instant.now());
    assertThat(message.role()).isEqualTo(MessageRole.USER);
    assertThat(message.status()).isEqualTo(MessageStatus.COMPLETED);
}
~~~

- [ ] **Step 2: Verify failure**

Run: mvn -s maven-settings.xml -pl copilot-domain -Dtest=ConversationStateTest test

Expected: FAIL because the package does not exist.

- [ ] **Step 3: Implement records, enums and ports**

~~~java
public interface ConversationPort {
    ConversationRun createAndStart(Long userId, UUID conversationId, UUID runId,
                                   String prompt, Instant now);
    ConversationRun startRun(Long userId, UUID conversationId, UUID runId,
                             String prompt, Instant now);
    Optional<ConversationRun> findRun(Long userId, UUID runId);
    boolean completeAssistant(Long userId, UUID runId, String content,
                              Map<String, Object> metadata, Instant at);
    boolean failAssistant(Long userId, UUID runId, String code, String message, Instant at);
    boolean cancelAssistant(Long userId, UUID runId, String message, Instant at);
    CursorPage<ResearchConversation> listConversations(Long userId, String cursor, int limit);
    CursorPage<ConversationMessage> listMessages(Long userId, UUID conversationId,
                                                 Long beforeSequence, int limit);
    List<ConversationMessage> recentCompletedMessages(Long userId, UUID conversationId, int limit);
    boolean archive(Long userId, UUID conversationId, Instant at);
}
~~~

~~~java
public interface AgentToolAuditPort {
    void start(AgentToolAudit audit);
    void complete(Long userId, UUID runId, String toolCallId, ToolAuditStatus status,
                  String summary, String hash, List<String> artifactIds,
                  String errorCode, String errorMessage, Instant at, long durationMs);
    void cancelOpenForRun(Long userId, UUID runId, ToolAuditStatus status,
                          String errorCode, String errorMessage, Instant at);
    CursorPage<AgentToolAudit> listByRun(Long userId, UUID runId, String cursor, int limit);
}
~~~

ConversationMessage owns the RUNNING-to-terminal validation. CursorPage defensively copies items. Reject null IDs and sequence values below one.

- [ ] **Step 4: Verify and commit**

Run: mvn -s maven-settings.xml -pl copilot-domain test

Expected: PASS.

~~~bash
git add copilot-domain/src/main/java/com/financial/copilot/domain/conversation copilot-domain/src/test/java/com/financial/copilot/domain/conversation
git commit -m "feat(conversation): add durable history domain contracts"
~~~

### Task 2: Implement PostgreSQL storage

**Files:**
- Create: copilot-app/src/main/resources/db/conversation-schema.sql
- Modify: copilot-app/src/main/resources/application.yml
- Create: copilot-data-engine/src/main/java/com/financial/copilot/data/conversation/po/ResearchConversationPO.java
- Create: copilot-data-engine/src/main/java/com/financial/copilot/data/conversation/po/ConversationMessagePO.java
- Create: copilot-data-engine/src/main/java/com/financial/copilot/data/conversation/po/AgentToolAuditPO.java
- Create: copilot-data-engine/src/main/java/com/financial/copilot/data/conversation/mapper/ResearchConversationMapper.java
- Create: copilot-data-engine/src/main/java/com/financial/copilot/data/conversation/mapper/ConversationMessageMapper.java
- Create: copilot-data-engine/src/main/java/com/financial/copilot/data/conversation/mapper/AgentToolAuditMapper.java
- Create: copilot-data-engine/src/main/java/com/financial/copilot/data/conversation/adapter/PostgresConversationAdapter.java
- Test: copilot-data-engine/src/test/java/com/financial/copilot/data/conversation/PostgresConversationAdapterTest.java

**Interfaces:**
- Consumes both Task 1 ports.
- Produces atomic message creation, conditional terminal updates, stable cursor queries and idempotent audit upserts.

- [ ] **Step 1: Write failing adapter tests**

~~~java
@Test
void existingConversationIsLockedByOwnerBeforeSequenceAllocation() {
    when(conversations.lockActive(7L, conversationId)).thenReturn(new ResearchConversationPO());
    when(messages.nextSequence(conversationId)).thenReturn(5L);
    when(messages.insertMessage(any())).thenReturn(1);

    ConversationRun run = adapter.startRun(7L, conversationId, runId, "prompt", Instant.now());

    assertThat(run.conversationId()).isEqualTo(conversationId);
    InOrder order = inOrder(conversations, messages);
    order.verify(conversations).lockActive(7L, conversationId);
    order.verify(messages).nextSequence(conversationId);
    verify(messages, times(2)).insertMessage(any());
}

@Test
void completionIncludesOwnerRunRoleAndRunningPredicate() {
    when(messages.completeAssistant(eq(7L), eq(runId), eq("report"), eq("{}"), any()))
            .thenReturn(1);
    assertThat(adapter.completeAssistant(7L, runId, "report", Map.of(), Instant.now())).isTrue();
}
~~~

- [ ] **Step 2: Verify failure**

Run: mvn -s maven-settings.xml -pl copilot-data-engine -am -Dtest=PostgresConversationAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test

Expected: FAIL because adapter files do not exist.

- [ ] **Step 3: Add DDL and initialization**

Create research_conversation, conversation_message and agent_tool_audit exactly as the spec defines, using BIGINT user_id. Add CHECK constraints, ON DELETE CASCADE, unique (conversation_id, sequence_no), unique (user_id, run_id, role), unique (user_id, run_id, tool_call_id), and the partial user/last-message index. Add classpath:db/conversation-schema.sql to spring.sql.init.schema-locations.

Add the runtime switch to application.yml:

~~~yaml
copilot:
  conversation:
    persistence-enabled: ${CONVERSATION_PERSISTENCE_ENABLED:true}
~~~

- [ ] **Step 4: Implement owner-scoped SQL**

~~~sql
SELECT * FROM research_conversation
WHERE user_id = #{userId} AND id = #{conversationId}
  AND status = 'ACTIVE' AND deleted_at IS NULL
FOR UPDATE
~~~

After the row lock, allocate with:

~~~sql
SELECT COALESCE(MAX(sequence_no), 0) + 1
FROM conversation_message WHERE conversation_id = #{conversationId}
~~~

Insert USER at n and ASSISTANT at n+1. Every terminal update must filter user_id, run_id, role='ASSISTANT' and status='RUNNING'. Use CAST(#{json} AS jsonb). Tool start uses ON CONFLICT (user_id, run_id, tool_call_id) DO NOTHING.

- [ ] **Step 5: Implement adapter mapping and pagination**

Annotate createAndStart and startRun with @Transactional("jdbcTransactionManager"). startRun throws the domain ConversationNotFoundException when the owned active conversation is missing. listMessages and listByRun perform the same owner existence check before returning data, so missing and foreign identifiers cannot be distinguished. createAndStart inserts the caller-supplied server UUID and a title made from the normalized first 40 Unicode code points. Encode cursors as URL-safe Base64 JSON; reject malformed cursors. Query limit+1 rows. Keep PO JSON fields as String and map JSON with ObjectMapper.

- [ ] **Step 6: Verify and commit**

Run: mvn -s maven-settings.xml -pl copilot-data-engine -am test

Expected: PASS.

~~~bash
git add copilot-data-engine copilot-app/src/main/resources
git commit -m "feat(conversation): persist messages and tool audits"
~~~

### Task 3: Add lifecycle service and Redis reconstruction

**Files:**
- Create: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/conversation/ConversationService.java
- Create: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/conversation/ConversationPersistenceProperties.java
- Create: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/conversation/ConversationPersistenceDisabledException.java
- Modify: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/memory/ShortTermMemoryService.java
- Test: copilot-agent-core/src/test/java/com/financial/copilot/agent/core/conversation/ConversationServiceTest.java
- Test: copilot-agent-core/src/test/java/com/financial/copilot/agent/core/memory/ShortTermMemoryServiceTest.java

**Interfaces:**
- Produces beginRun, complete, fail, cancel, recentContext and query delegation.
- Produces ShortTermMemoryService.replaceContext.
- Produces configuration property copilot.conversation.persistence-enabled, default true.

- [ ] **Step 1: Write failing lifecycle tests**

~~~java
@Test
void completionPersistsBeforeCacheProjection() {
    when(port.completeAssistant(eq(7L), eq(run.runId()), eq("report"), anyMap(), any()))
            .thenReturn(true);

    service.complete(7L, run, "key", "prompt", "report", Map.of("graphRevision", 2));

    InOrder order = inOrder(port, memory);
    order.verify(port).completeAssistant(eq(7L), eq(run.runId()), eq("report"), anyMap(), any());
    order.verify(memory).replaceContext("key", List.of("USER: prompt", "ASSISTANT: report"));
}

@Test
void cacheMissRebuildsOnlyCompletedMessages() {
    when(memory.getContext("key")).thenReturn(List.of());
    when(port.recentCompletedMessages(7L, conversationId, 20)).thenReturn(completedMessages());
    assertThat(service.recentContext(7L, conversationId, "key", 20))
            .containsExactly("USER: prompt", "ASSISTANT: report");
}

@Test
void disabledModeSkipsConversationTablesButKeepsMemoryProjection() {
    properties.setPersistenceEnabled(false);
    ConversationRun run = service.beginRun(7L, null, runId, "prompt");
    service.complete(7L, run, "key", "prompt", "report", Map.of());
    verifyNoInteractions(port, audits);
    verify(memory).replaceContext("key", List.of("USER: prompt", "ASSISTANT: report"));
    verify(longMemory).record("key", "report");
}
~~~

- [ ] **Step 2: Verify failure**

Run: mvn -s maven-settings.xml -pl copilot-agent-core -am -Dtest=ConversationServiceTest,ShortTermMemoryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test

Expected: FAIL.

- [ ] **Step 3: Implement service API**

~~~java
ConversationRun beginRun(Long userId, UUID requestedId, UUID runId, String prompt);
void complete(Long userId, ConversationRun run, String sessionKey, String prompt, String report,
              Map<String, Object> metadata);
void fail(Long userId, UUID runId, Throwable error);
void cancel(Long userId, UUID runId, String safeMessage);
List<String> recentContext(Long userId, UUID conversationId, String sessionKey, int limit);
~~~

beginRun generates the conversation UUID before calling createAndStart when requestedId is absent; otherwise it calls startRun. The HTTP layer derives sessionKey from the returned server conversation ID. complete writes PostgreSQL before Redis, then calls LongTermMemoryService.record and publishes WorkflowFinishedEvent so refined facts remain a derived projection. fail/cancel use stable codes and sanitized messages up to 500 characters, and close RUNNING tool audits. Optional MeterRegistry records the approved counters without content tags.

ConversationPersistenceProperties is a Spring @ConfigurationProperties component with prefix copilot.conversation and persistenceEnabled=true. When false, beginRun returns an ephemeral ConversationRun with null message IDs, terminal methods skip both ports, while successful completion still updates ShortTermMemoryService, LongTermMemoryService and WorkflowFinishedEvent. History query methods throw ConversationPersistenceDisabledException.

Wrap terminal message writes in a local three-attempt retry for transient DataAccessException values, with 50 ms then 200 ms delay. The third failure propagates so the HTTP lifecycle cannot claim persistence succeeded; the existing checkpoint and final Artifact remain available for compensation.

- [ ] **Step 4: Add deterministic cache replacement**

~~~java
public void replaceContext(String sessionKey, List<String> values) {
    String key = memoryKey(sessionKey);
    redisTemplate.delete(key);
    if (values != null && !values.isEmpty()) {
        redisTemplate.opsForList().rightPushAll(key, values);
        redisTemplate.expire(key, Duration.ofMinutes(30));
        pruneIfNeeded(sessionKey);
    }
}
~~~

On cache miss, read recent COMPLETED messages from PostgreSQL, reverse them to chronological order, prefix roles, replace Redis and return the result.

- [ ] **Step 5: Verify and commit**

Run: mvn -s maven-settings.xml -pl copilot-agent-core -am test

Expected: PASS.

~~~bash
git add copilot-agent-core/src/main/java/com/financial/copilot/agent/core/conversation copilot-agent-core/src/main/java/com/financial/copilot/agent/core/memory copilot-agent-core/src/test
git commit -m "feat(conversation): manage durable run lifecycle"
~~~

### Task 4: Propagate identity through Graph and checkpoint resume

**Files:**
- Modify: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/GraphRunRequest.java
- Modify: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/NodeExecutionContext.java
- Modify: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/DagRuntime.java
- Modify: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/runtime/checkpoint/DagCheckpoint.java
- Modify: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/GraphPlanningRequest.java
- Modify: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/GraphPlannerAgent.java
- Modify: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/planner/tool/MarketMemoryTool.java
- Modify: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/workflow/FinancialResearchWorkflow.java
- Modify: copilot-common/src/main/java/com/financial/copilot/common/event/ResearchStreamEvent.java
- Modify: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/dag/event/NodeEventBus.java
- Test: copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/runtime/ConversationCheckpointTest.java
- Modify: copilot-agent-core/src/test/java/com/financial/copilot/agent/core/agents/NativeFundScreenerAgentTest.java
- Modify: copilot-agent-core/src/test/java/com/financial/copilot/agent/core/agents/NativeStockComparatorAgentTest.java
- Modify: copilot-agent-core/src/test/java/com/financial/copilot/agent/core/agentscope/AgentScopeAgentFactoryTest.java
- Modify: copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/planner/tool/MarketMemoryToolTest.java
- Modify: copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/runtime/checkpoint/DagCheckpointRoundTripTest.java
- Modify: copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/runtime/DagRunIsolationTest.java
- Modify: copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/runtime/DagRuntimeTest.java
- Modify: copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/runtime/DynamicReplanTest.java
- Modify: copilot-agent-core/src/test/java/com/financial/copilot/agent/core/dag/event/NodeEventBusTest.java
- Modify: copilot-agent-core/src/test/java/com/financial/copilot/agent/core/memory/LongTermMemoryRecallTest.java
- Modify: copilot-agent-core/src/test/java/com/financial/copilot/agent/core/workflow/FinancialResearchWorkflowTest.java

**Interfaces:**
- Produces GraphRunRequest(String runId, Long userId, UUID conversationId, Long assistantMessageId, String sessionKey, String prompt, boolean enableThinking, UserInvestmentProfile profile, Consumer<LlmResponse> usageConsumer, RunMode mode).
- Produces NodeExecutionContext(GraphRunRequest request, String nodeId, ArtifactStore artifacts, CancellationToken cancellationToken).

- [ ] **Step 1: Write failing checkpoint test**

~~~java
@Test
void checkpointAndResumeKeepConversationIdentity() {
    GraphRunRequest request = request("run-1", conversationId, 42L, "key");
    runtime.run(request, oneNodeGraph()).completion().join();
    DagCheckpoint saved = store.load(7L, "run-1").orElseThrow();
    assertThat(saved.conversationId()).isEqualTo(conversationId);
    assertThat(saved.assistantMessageId()).isEqualTo(42L);
    assertThat(saved.sessionKey()).isEqualTo("key");
}
~~~

- [ ] **Step 2: Verify failure**

Run: mvn -s maven-settings.xml -pl copilot-agent-core -am -Dtest=ConversationCheckpointTest -Dsurefire.failIfNoSpecifiedTests=false test

Expected: FAIL.

- [ ] **Step 3: Replace runtime session fields**

Apply the exact record signatures above. Rename GraphPlanningRequest.sessionId to sessionKey. RuntimeContext uses sessionKey as AgentScope sessionId. DagRuntime supplies real node IDs; GraphPlannerAgent supplies "__planner__" and "__replanner__".

- [ ] **Step 4: Persist and restore linkage**

Every checkpoint copies conversationId, nullable assistantMessageId and sessionKey. resume reconstructs GraphRunRequest from them. Remove request.sessionId and saved.sessionId references.

Add conversationId to ResearchStreamEvent and change graphInitialized plus NodeEventBus.publishGraphInitialized to require it. DagRuntime passes request.conversationId().toString(), making the first SSE graph_initialized event contain both conversationId and runId.

- [ ] **Step 5: Remove workflow persistence side effects**

Delete shortMemory, longMemory, eventPublisher and recordCompletion from FinancialResearchWorkflow. run only plans, registers and returns. Delete WorkflowFinishedEvent/listeners if rg proves there is no remaining producer or consumer.

- [ ] **Step 6: Verify and commit**

Run: mvn -s maven-settings.xml -pl copilot-agent-core -am test

Expected: PASS.

~~~bash
git add copilot-agent-core/src
git commit -m "refactor(runtime): carry durable conversation identity"
~~~

### Task 5: Persist AgentScope ReAct tool audits

**Files:**
- Create: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agentscope/AgentToolAuditSink.java
- Create: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agentscope/ToolAuditEvent.java
- Create: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/conversation/ToolArgumentSanitizer.java
- Create: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/conversation/PersistentAgentToolAuditSink.java
- Modify: copilot-agent-core/src/main/java/com/financial/copilot/agent/core/agentscope/AgentScopeAgentFactory.java
- Test: copilot-agent-core/src/test/java/com/financial/copilot/agent/core/agentscope/AgentScopeToolAuditTest.java
- Test: copilot-agent-core/src/test/java/com/financial/copilot/agent/core/conversation/ToolArgumentSanitizerTest.java

**Interfaces:**
- Produces AgentToolAuditSink.accept(ToolAuditEvent).
- Produces one idempotent start and terminal event per toolCallId.

- [ ] **Step 1: Write failing audit tests**

~~~java
@Test
void recordsStartAndResultWithoutSecretOrSystemPrompt() {
    List<ToolAuditEvent> events = new CopyOnWriteArrayList<>();
    AgentScopeAgentFactory factory = scriptedFactory(events::add,
            toolUse("call-1", "lookup", Map.of("query", "fund", "apiKey", "secret")),
            toolResult("call-1", "lookup", "large-result"));

    factory.invokeWithTrace(definition(), "prompt", auditedContext());

    assertThat(events).extracting(ToolAuditEvent::status)
            .containsExactly(ToolAuditStatus.RUNNING, ToolAuditStatus.SUCCEEDED);
    assertThat(events.get(0).arguments()).doesNotContainKey("apiKey");
    assertThat(events.toString()).doesNotContain("secret");
}
~~~

- [ ] **Step 2: Verify failure**

Run: mvn -s maven-settings.xml -pl copilot-agent-core -am -Dtest=AgentScopeToolAuditTest,ToolArgumentSanitizerTest -Dsurefire.failIfNoSpecifiedTests=false test

Expected: FAIL.

- [ ] **Step 3: Implement sanitization**

Remove keys matching authorization, cookie, set-cookie, api-key, apikey, access-token, accesstoken, refresh-token, password, private-key, secret and signature, case-insensitively. Limit nesting to 8, collections to 100 elements, strings to 1000 code points and serialized arguments to 16 KiB. Persist a summary up to 1000 code points and SHA-256 of the unsaved full result.

- [ ] **Step 4: Observe AgentScope blocks**

MeteredModel emits RUNNING for each new ToolUseBlock in ChatResponse and SUCCEEDED/FAILED for matching ToolResultBlock in inbound Msg. Track start Instant and observed IDs in concurrent collections, calculate duration, and catch sink failures so audit storage never changes Agent output. Never send systemPrompt or full Msg lists into ToolAuditEvent. Add AgentToolAuditSink.noop() for tests.

- [ ] **Step 5: Implement persistent sink**

When ConversationPersistenceProperties.persistenceEnabled is false, return before calling AgentToolAuditPort. Otherwise map RUNNING to port.start and terminal events to port.complete. Extract artifact IDs only from explicit artifactId/artifactIds fields. Retry a transient DataAccessException once after 50 ms, then log, increment agent_tool_audit_write_failure_total and return without changing the Agent result. ConversationService cancel/fail closes remaining RUNNING audits.

- [ ] **Step 6: Verify and commit**

Run: mvn -s maven-settings.xml -pl copilot-agent-core -am -Dtest=AgentScopeAgentFactoryTest,AgentScopeToolAuditTest,NativeFundScreenerAgentTest,NativeStockComparatorAgentTest,GraphPlannerAgentTest -Dsurefire.failIfNoSpecifiedTests=false test

Expected: PASS.

~~~bash
git add copilot-agent-core/src
git commit -m "feat(agentscope): persist sanitized tool audits"
~~~

### Task 6: Replace HTTP session entry with conversation lifecycle

**Files:**
- Modify: copilot-app/src/main/java/com/financial/copilot/controller/ResearchAgentController.java
- Modify: copilot-app/src/main/java/com/financial/copilot/controller/ResearchRunController.java
- Create: copilot-app/src/main/java/com/financial/copilot/controller/ConversationController.java
- Modify: copilot-app/src/test/java/com/financial/copilot/controller/ResearchAgentControllerTest.java
- Modify: copilot-app/src/test/java/com/financial/copilot/controller/ResearchRunControllerTest.java
- Create: copilot-app/src/test/java/com/financial/copilot/controller/ConversationControllerTest.java

**Interfaces:**
- Produces POST /api/v1/research/runs with optional conversationId and no sessionId.
- Produces GET /api/v1/conversations, GET messages, POST archive and GET run tool-audits.

- [ ] **Step 1: Write failing controller tests**

~~~java
@Test
void jsonRunBeginsConversationBeforeWorkflowAndCompletes() {
    when(conversations.beginRun(eq(7L), isNull(), any(), eq("prompt")))
            .thenReturn(persisted);
    when(workflow.run(any())).thenReturn(successfulHandle(runId, "report"));

    ResearchRunResponse response = authenticatedJsonRun("prompt");

    assertThat(response.getConversationId()).isEqualTo(persisted.conversationId());
    InOrder order = inOrder(conversations, workflow);
    order.verify(conversations).beginRun(eq(7L), isNull(), any(), eq("prompt"));
    order.verify(workflow).run(argThat(r -> r.conversationId().equals(persisted.conversationId())));
    verify(conversations).complete(eq(7L), eq(persisted), anyString(), eq("prompt"), eq("report"), anyMap());
}

@Test
void foreignConversationReturnsSameNotFoundAsMissing() {
    when(service.listMessages(8L, foreignId, null, 20))
            .thenThrow(new ConversationNotFoundException());
    assertThatThrownBy(() -> authenticatedMessages(8L, foreignId))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
}

@Test
void disabledPersistenceReturnsServiceUnavailableForHistory() {
    when(service.listConversations(7L, null, 20))
            .thenThrow(new ConversationPersistenceDisabledException());
    assertThatThrownBy(() -> authenticatedConversationList(7L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("503").hasMessageContaining("CONVERSATION_PERSISTENCE_DISABLED");
}
~~~

- [ ] **Step 2: Verify failure**

Run: mvn -s maven-settings.xml -pl copilot-app -am -Dtest=ResearchAgentControllerTest,ResearchRunControllerTest,ConversationControllerTest -Dsurefire.failIfNoSpecifiedTests=false test

Expected: FAIL.

- [ ] **Step 3: Refactor the unified run endpoint**

Request fields are prompt, UUID conversationId and enableThinking. Response fields include runId and conversationId. For both media types: validate, check balance, call beginRun, build GraphRunRequest, call workflow.run, then attach one completion observer. Success completes the message; CancellationException cancels it; any other error fails it. SSE doOnCancel calls handle.cancel("SSE client disconnected"). Conditional updates make repeated callbacks idempotent.

- [ ] **Step 4: Delete the legacy session API**

Remove SessionMemoryResponse, RefinedFactDTO, direct memory service dependencies and GET /memory/session/{sessionId}. Do not add aliases.

- [ ] **Step 5: Add history endpoints**

~~~java
@GetMapping
Mono<ApiResult<CursorPage<ResearchConversation>>> list(String cursor, int limit);

@GetMapping("/{conversationId}/messages")
Mono<ApiResult<CursorPage<ConversationMessage>>> messages(
        UUID conversationId, Long beforeSequence, int limit);

@PostMapping("/{conversationId}/archive")
Mono<ApiResult<Map<String, Object>>> archive(UUID conversationId);
~~~

Add GET /api/v1/research/runs/{runId}/tool-audits. Enforce limit 1..100. Missing/foreign resources return 404 and malformed cursors return 400. No endpoint accepts userId.

Map ConversationPersistenceDisabledException to HTTP 503 with code CONVERSATION_PERSISTENCE_DISABLED for conversation, message and tool-audit queries. Run creation remains available.

- [ ] **Step 6: Make resume/cancel durable**

When persistence is enabled, resolve persisted run ownership before resume/cancel. When disabled, use the existing userId-scoped Checkpoint and Run Registry ownership checks. Resume uses checkpoint identity and the same completion observer. Cancel stops the active handle; it marks the assistant CANCELLED only when persistence is enabled.

- [ ] **Step 7: Verify and commit**

Run: mvn -s maven-settings.xml -pl copilot-app -am test

Expected: PASS.

~~~bash
git add copilot-app/src/main/java/com/financial/copilot/controller copilot-app/src/test/java/com/financial/copilot/controller
git commit -m "feat(api): unify research runs around conversations"
~~~

### Task 7: Verify real persistence, isolation and concurrency

**Files:**
- Create: copilot-app/src/test/java/com/financial/copilot/ConversationPersistenceTest.java
- Create: copilot-app/src/test/java/com/financial/copilot/ConversationPersistenceDisabledTest.java
- Modify: copilot-app/src/test/java/com/financial/copilot/ApplicationStartupTest.java
- Modify: README.md

**Interfaces:**
- Produces opt-in PostgreSQL/Redis proof and current API documentation.

- [ ] **Step 1: Write integration tests**

~~~java
@Test
void historySurvivesRedisLossAndForeignUserCannotReadIt() {
    ConversationRun run = service.beginRun(owner, null, UUID.randomUUID(), "prompt");
    String sessionKey = SecurityUtils.sessionKey(owner, run.conversationId().toString());
    service.complete(owner, run, sessionKey, "prompt", "report", Map.of());
    redis.delete("shortterm:session:" + sessionKey);

    assertThat(service.recentContext(owner, run.conversationId(), sessionKey, 20))
            .containsExactly("USER: prompt", "ASSISTANT: report");
    assertThatThrownBy(() -> port.listMessages(stranger, run.conversationId(), null, 20))
            .isInstanceOf(ConversationNotFoundException.class);
}

@Test
void concurrentStartsAllocateSixUniqueSequences() throws Exception {
    ConversationRun first = beginFirstRun();
    startTwoRunsTogether(first.conversationId());
    assertThat(port.listMessages(owner, first.conversationId(), null, 20).items())
            .extracting(ConversationMessage::sequenceNo)
            .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L)
            .doesNotHaveDuplicates();
}
~~~

Use @EnabledIfSystemProperty(named="copilot.integration", matches="true"), CountDownLatch, virtual threads and exact-ID cleanup in finally blocks.

Add ConversationPersistenceDisabledTest with @EnabledIfSystemProperty(named="copilot.integration", matches="true") and @SpringBootTest(properties="copilot.conversation.persistence-enabled=false"), then add this assertion:

~~~java
@Test
void disabledModeWritesMemoryAndCheckpointButNoConversationRows() {
    ConversationRun run = service.beginRun(owner, null, UUID.randomUUID(), "prompt");
    String key = SecurityUtils.sessionKey(owner, run.conversationId().toString());
    service.complete(owner, run, key, "prompt", "report", Map.of());
    checkpointStore.saveCheckpoint(new DagCheckpoint(run.runId().toString(), owner,
            run.conversationId(), null, key, "prompt", false, null,
            new ExecutionGraph("disabled").snapshot(), Map.of(), Map.of(), Instant.now()));

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation_message WHERE run_id = ?",
            Long.class, run.runId())).isZero();
    assertThat(shortMemory.getContext(key)).contains("ASSISTANT: report");
    assertThat(longMemory.retrieve(key, 10)).contains("report");
    assertThat(checkpointStore.load(owner, run.runId().toString())).isPresent();
}
~~~

- [ ] **Step 2: Verify environment gating and real services**

Run: mvn -s maven-settings.xml -pl copilot-app -am -Dtest=ConversationPersistenceTest,ConversationPersistenceDisabledTest -Dsurefire.failIfNoSpecifiedTests=false test

Expected: SKIPPED.

Run: mvn -s maven-settings.xml -pl copilot-app -am "-Dcopilot.integration=true" -Dtest=ConversationPersistenceTest,ConversationPersistenceDisabledTest -Dsurefire.failIfNoSpecifiedTests=false test

Expected: PASS against configured PostgreSQL and Redis without remote LLM calls.

- [ ] **Step 3: Update startup test and README**

Assert all three new tables exist in ApplicationStartupTest. Document conversationId requests, history/audit endpoints, PostgreSQL source-of-truth behavior, Redis 30-minute cache and Checkpoint 24-hour recovery. Document CONVERSATION_PERSISTENCE_ENABLED=false for test environments and state that memory and checkpoints remain enabled. Remove sessionId and memory/session examples.

- [ ] **Step 4: Verify old entry removal**

Run: rg -n "getSessionId|setSessionId|memory/session|request\.sessionId\(\)|saved\.sessionId\(\)" copilot-app/src copilot-agent-core/src

Expected: no production matches.

- [ ] **Step 5: Run all tests**

Run: mvn -s maven-settings.xml clean test

Expected: PASS; opt-in integration tests skip.

Run: mvn -s maven-settings.xml test "-Dcopilot.integration=true"

Expected: PASS.

- [ ] **Step 6: Commit verification and docs**

~~~bash
git add copilot-app/src/test README.md
git commit -m "test(conversation): verify durable isolated history"
~~~

### Task 8: Final regression gate

**Files:**
- Review all changed files and both spec/plan documents.
- Change only focused defects found by the checks.

**Interfaces:**
- Produces the final validated implementation.

- [ ] **Step 1: Check diff and workspace**

Run: git diff --check HEAD~7..HEAD

Expected: no errors.

Run: git status --short

Expected: only the pre-existing untracked .agents/plugins junctions remain.

- [ ] **Step 2: Scan persistence boundaries**

Run: rg -n "systemPrompt|sysPrompt|reasoningContent|Authorization|apiKey|privateKey" copilot-domain/src/main/java/com/financial/copilot/domain/conversation copilot-data-engine/src/main/java/com/financial/copilot/data/conversation copilot-agent-core/src/main/java/com/financial/copilot/agent/core/conversation

Expected: only sanitizer deny-list matches; persisted entities and mappers expose none of these fields.

- [ ] **Step 3: Run focused regression tests**

Run: mvn -s maven-settings.xml -pl copilot-agent-core,copilot-app -am -Dtest=ConversationCheckpointTest,AgentScopeToolAuditTest,ResearchRunControllerTest,ConversationControllerTest -Dsurefire.failIfNoSpecifiedTests=false test

Expected: PASS.

- [ ] **Step 4: Run the full gate**

Run: mvn -s maven-settings.xml clean test

Expected: PASS.

Run when services are available: mvn -s maven-settings.xml test "-Dcopilot.integration=true"

Expected: PASS.
