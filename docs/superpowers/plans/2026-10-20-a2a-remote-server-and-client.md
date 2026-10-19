# Open-Source A2A Remote Server & Client Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement full open-source A2A (Agent-to-Agent) remote server endpoints (AgentCard discovery, JSON-RPC, SSE event streaming) and a remote client adapter allowing seamless bidirectional inter-agent networking.

**Architecture:** 
1. **A2A Protocol & Task Layer (`copilot-agent-core`):** Define standard JSON-RPC 2.0 DTOs, Task lifecycle states, and `A2aTaskManager` managing long-running task events via Reactive Sinks (`Sinks.Many<A2aTaskEvent>`).
2. **A2A Client Adapter (`RemoteA2aAgent`):** Implement `A2aExecutableAgent` to bridge external remote A2A services (fetching remote `.well-known/agent.json`, executing via JSON-RPC/SSE) so they integrate into `A2aAgentRegistry` transparently.
3. **A2A Server Controller (`copilot-app`):** Expose standard `/.well-known/agent.json`, `/api/v1/a2a/agents/{name}/.well-known/agent.json`, JSON-RPC dispatch (`tasks.create`, `tasks.cancel`, `tasks.get_task`), and SSE event streams (`text/event-stream`).
4. **Security Integration:** Update `WebFluxSecurityConfig` with public discovery rules and authenticated task invocation.

**Tech Stack:** Java 21, Spring Boot 3.3.3 WebFlux, Project Reactor (`Flux`, `Mono`, `Sinks`), a2a-java-sdk-spec 0.2.5.Beta2, Jackson, JUnit 5.

**Spec:** Open-source Agent-to-Agent (A2A) protocol specification (AgentCard discovery, AgentExecutor JSON-RPC, Task SSE streaming, Artifact separation).

---

## Global Constraints
- Strictly adhere to Java 21 and Spring WebFlux non-blocking reactive paradigms.
- Follow `.agents/rules/coding-standards.md` (Chinese Javadoc, clean imports, Lombok hygiene).
- Preserve existing backward compatibility for DAG execution (`DagRuntime`, `SupervisorWorkflow`).
- No unauthorized external dependency additions.
- Ensure all Maven tests pass with `mvn -s maven-settings.xml -pl <module> test`.

---

## Task Breakdown

### Task 1: A2A JSON-RPC Protocol Models & Task State Enums

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/a2a/protocol/A2aJsonRpcRequest.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/a2a/protocol/A2aJsonRpcResponse.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/a2a/protocol/A2aTaskStatus.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/a2a/protocol/A2aTaskEvent.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/a2a/protocol/A2aProtocolSerializationTest.java`

**Interfaces:**
- `A2aJsonRpcRequest`: `jsonrpc`, `id`, `method` (`tasks.create`, `tasks.cancel`, `tasks.get_task`), `params` (`Map<String, Object>`).
- `A2aJsonRpcResponse`: `jsonrpc`, `id`, `result`, `error`.
- `A2aTaskStatus`: `SUBMITTED`, `WORKING`, `COMPLETED`, `FAILED`, `CANCELLED`.
- `A2aTaskEvent`: `type` (`status_update`, `text_delta`, `artifact_update`, `error`), `taskId`, `payload`, `timestamp`.

- [ ] **Step 1: Write failing serialization test `A2aProtocolSerializationTest.java`**

```java
package com.financial.copilot.agent.core.business.agents.a2a.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("A2A JSON-RPC 协议序列化测试")
class A2aProtocolSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("验证 A2aJsonRpcRequest 与 A2aTaskEvent 序列化反序列化符合规范")
    void testSerialization() throws Exception {
        A2aJsonRpcRequest req = new A2aJsonRpcRequest("2.0", "req-101", "tasks.create",
                Map.of("prompt", "分析招商中证白酒", "assetCategory", "FUND"));
        String json = mapper.writeValueAsString(req);
        assertTrue(json.contains("tasks.create"));
        assertTrue(json.contains("req-101"));

        A2aJsonRpcRequest readReq = mapper.readValue(json, A2aJsonRpcRequest.class);
        assertEquals("tasks.create", readReq.method());
        assertEquals("分析招商中证白酒", readReq.params().get("prompt"));

        A2aTaskEvent event = new A2aTaskEvent("text_delta", "task-1", "正在获取指标...", Instant.now());
        String eventJson = mapper.writeValueAsString(event);
        assertTrue(eventJson.contains("text_delta"));
        assertTrue(eventJson.contains("task-1"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=A2aProtocolSerializationTest`
Expected: FAIL (types not found)

- [ ] **Step 3: Implement protocol models**

Write `A2aJsonRpcRequest.java`:
```java
package com.financial.copilot.agent.core.business.agents.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** A2A JSON-RPC 2.0 请求载荷 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aJsonRpcRequest(
        String jsonrpc,
        Object id,
        String method,
        Map<String, Object> params
) {
    public A2aJsonRpcRequest {
        jsonrpc = jsonrpc != null ? jsonrpc : "2.0";
    }
}
```

Write `A2aJsonRpcResponse.java`:
```java
package com.financial.copilot.agent.core.business.agents.a2a.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A2A JSON-RPC 2.0 响应载荷 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aJsonRpcResponse(
        String jsonrpc,
        Object id,
        Object result,
        A2aJsonRpcError error
) {
    public static A2aJsonRpcResponse success(Object id, Object result) {
        return new A2aJsonRpcResponse("2.0", id, result, null);
    }

    public static A2aJsonRpcResponse error(Object id, int code, String message) {
        return new A2aJsonRpcResponse("2.0", id, null, new A2aJsonRpcError(code, message));
    }

    public record A2aJsonRpcError(int code, String message) {}
}
```

Write `A2aTaskStatus.java`:
```java
package com.financial.copilot.agent.core.business.agents.a2a.protocol;

/** A2A 长时任务生命周期状态枚举 */
public enum A2aTaskStatus {
    SUBMITTED,
    WORKING,
    COMPLETED,
    FAILED,
    CANCELLED
}
```

Write `A2aTaskEvent.java`:
```java
package com.financial.copilot.agent.core.business.agents.a2a.protocol;

import java.time.Instant;

/** A2A Task SSE 流式推送事件 */
public record A2aTaskEvent(
        String type,
        String taskId,
        Object payload,
        Instant timestamp
) {
    public static A2aTaskEvent status(String taskId, A2aTaskStatus status) {
        return new A2aTaskEvent("status_update", taskId, status.name(), Instant.now());
    }

    public static A2aTaskEvent textDelta(String taskId, String text) {
        return new A2aTaskEvent("text_delta", taskId, text, Instant.now());
    }

    public static A2aTaskEvent artifact(String taskId, Object artifactPayload) {
        return new A2aTaskEvent("artifact_update", taskId, artifactPayload, Instant.now());
    }

    public static A2aTaskEvent error(String taskId, String errorMsg) {
        return new A2aTaskEvent("error", taskId, errorMsg, Instant.now());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=A2aProtocolSerializationTest`
Expected: PASS

- [ ] **Step 5: Commit**

```powershell
git add copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/a2a/protocol/
git add copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/a2a/protocol/
git commit -m "feat(a2a): add JSON-RPC protocol models and task status events"
```

---

### Task 2: Implement A2A Task Manager (`A2aTaskManager`)

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/a2a/manager/A2aTaskManager.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/a2a/manager/A2aTaskSession.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/a2a/manager/A2aTaskManagerTest.java`

**Interfaces:**
- Consumes: `A2aAgentRegistry`, `A2aExecutableAgent`.
- Produces: `A2aTaskManager`:
  - `A2aTaskSession createTask(String agentName, Map<String, Object> params, Long userId)`
  - `Optional<A2aTaskSession> getTask(String taskId)`
  - `boolean cancelTask(String taskId, String reason)`
  - `Flux<A2aTaskEvent> streamEvents(String taskId)`

- [ ] **Step 1: Write failing test `A2aTaskManagerTest.java`**

```java
package com.financial.copilot.agent.core.business.agents.a2a.manager;

import com.financial.copilot.agent.core.business.agents.a2a.A2aAgentRegistry;
import com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent;
import com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskEvent;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskStatus;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("A2aTaskManager 任务管理器测试")
class A2aTaskManagerTest {

    @Test
    @DisplayName("验证创建任务、异步执行、状态流转与事件推送")
    void testCreateTaskAndStreamEvents() {
        A2aExecutableAgent mockAgent = mock(A2aExecutableAgent.class);
        when(mockAgent.agentCard()).thenReturn(AgentCardDefinitions.fundScreenerCard());
        when(mockAgent.execute(any(), any(), any())).thenReturn(
                Artifact.of("art-1", ArtifactType.FUND_POOL, "node-1", "fund-result-data"));

        A2aAgentRegistry registry = new A2aAgentRegistry(List.of(mockAgent));
        A2aTaskManager manager = new A2aTaskManager(registry);

        A2aTaskSession session = manager.createTask("FundScreenerAgent", Map.of("prompt", "消费基金"), 100L);
        assertNotNull(session);
        assertEquals(A2aTaskStatus.WORKING, session.getStatus());

        // Wait for task completion
        StepVerifier.create(manager.streamEvents(session.getTaskId()))
                .expectNextMatches(e -> "status_update".equals(e.type()))
                .expectNextMatches(e -> "artifact_update".equals(e.type()) && "fund-result-data".equals(e.payload()))
                .expectNextMatches(e -> "status_update".equals(e.type()) && A2aTaskStatus.COMPLETED.name().equals(e.payload()))
                .thenCancel()
                .verify();

        assertEquals(A2aTaskStatus.COMPLETED, session.getStatus());
    }

    @Test
    @DisplayName("验证取消正在执行的任务")
    void testCancelTask() {
        A2aExecutableAgent mockAgent = mock(A2aExecutableAgent.class);
        when(mockAgent.agentCard()).thenReturn(AgentCardDefinitions.fundScreenerCard());
        when(mockAgent.execute(any(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(500);
            return Artifact.of("art-1", ArtifactType.FUND_POOL, "node-1", "done");
        });

        A2aAgentRegistry registry = new A2aAgentRegistry(List.of(mockAgent));
        A2aTaskManager manager = new A2aTaskManager(registry);

        A2aTaskSession session = manager.createTask("FundScreenerAgent", Map.of("prompt", "长任务"), 100L);
        boolean cancelled = manager.cancelTask(session.getTaskId(), "用户主动取消");
        assertTrue(cancelled);
        assertEquals(A2aTaskStatus.CANCELLED, session.getStatus());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=A2aTaskManagerTest`
Expected: FAIL (classes not found)

- [ ] **Step 3: Implement `A2aTaskSession` and `A2aTaskManager`**

Write `A2aTaskSession.java`:
```java
package com.financial.copilot.agent.core.business.agents.a2a.manager;

import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskEvent;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskStatus;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A2A 长时任务会话上下文 */
public class A2aTaskSession {
    private final String taskId;
    private final String agentName;
    private final Map<String, Object> params;
    private final Long userId;
    private final Instant createdAt;
    private volatile Instant updatedAt;
    private volatile A2aTaskStatus status;
    private final CancellationToken cancellationToken;
    private final Sinks.Many<A2aTaskEvent> sink;
    private volatile Object artifactResult;

    public A2aTaskSession(String taskId, String agentName, Map<String, Object> params, Long userId) {
        this.taskId = taskId;
        this.agentName = agentName;
        this.params = params != null ? new ConcurrentHashMap<>(params) : new ConcurrentHashMap<>();
        this.userId = userId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.status = A2aTaskStatus.SUBMITTED;
        this.cancellationToken = new CancellationToken(taskId);
        this.sink = Sinks.many().replay().limit(100);
    }

    public void emitEvent(A2aTaskEvent event) {
        this.updatedAt = Instant.now();
        this.sink.tryEmitNext(event);
    }

    public void updateStatus(A2aTaskStatus newStatus) {
        this.status = newStatus;
        emitEvent(A2aTaskEvent.status(this.taskId, newStatus));
    }

    public String getTaskId() { return taskId; }
    public String getAgentName() { return agentName; }
    public Map<String, Object> getParams() { return params; }
    public Long getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public A2aTaskStatus getStatus() { return status; }
    public CancellationToken getCancellationToken() { return cancellationToken; }
    public Flux<A2aTaskEvent> getEvents() { return sink.asFlux(); }
    public Object getArtifactResult() { return artifactResult; }
    public void setArtifactResult(Object artifactResult) { this.artifactResult = artifactResult; }
}
```

Write `A2aTaskManager.java`:
```java
package com.financial.copilot.agent.core.business.agents.a2a.manager;

import com.financial.copilot.agent.core.business.agents.a2a.A2aAgentRegistry;
import com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskEvent;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskStatus;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.infra.dag.runtime.RunMode;
import com.financial.copilot.agent.core.infra.thread.VirtualThreadSchedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** A2A 长时任务管理与异步执行器 */
@Component
public class A2aTaskManager {

    private static final Logger log = LoggerFactory.getLogger(A2aTaskManager.class);

    private final A2aAgentRegistry registry;
    private final Map<String, A2aTaskSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public A2aTaskManager(A2aAgentRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public A2aTaskSession createTask(String agentName, Map<String, Object> params, Long userId) {
        A2aExecutableAgent agent = registry.getAgents().stream()
                .filter(a -> a.agentCard() != null && agentName.equalsIgnoreCase(a.agentCard().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Target A2A Agent not found: " + agentName));

        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        A2aTaskSession session = new A2aTaskSession(taskId, agentName, params, userId);
        sessions.put(taskId, session);

        session.updateStatus(A2aTaskStatus.WORKING);

        // Execute asynchronously on virtual thread
        VirtualThreadSchedulers.executor().execute(() -> {
            try {
                String prompt = params != null && params.get("prompt") != null ? String.valueOf(params.get("prompt")) : "";
                GraphRunRequest request = new GraphRunRequest(
                        taskId, userId, UUID.randomUUID(), null, "session-" + taskId,
                        prompt, false, null, null, RunMode.ASYNC
                );
                NodeExecutionContext context = new NodeExecutionContext(
                        request, taskId, new ArtifactStore(), session.getCancellationToken()
                );
                GraphNode node = GraphNode.builder()
                        .nodeId(taskId)
                        .taskType(agent.agentCard().skills().getFirst().tags().getFirst())
                        .params(params != null ? params : Map.of())
                        .build();

                Artifact<?> artifact = agent.execute(node, NodeInput.empty(), context);
                if (artifact != null) {
                    session.setArtifactResult(artifact.payload());
                    session.emitEvent(A2aTaskEvent.artifact(taskId, artifact.payload()));
                }
                session.updateStatus(A2aTaskStatus.COMPLETED);
            } catch (Exception e) {
                log.error("A2A Task execution failed: taskId={}", taskId, e);
                session.emitEvent(A2aTaskEvent.error(taskId, e.getMessage()));
                session.updateStatus(A2aTaskStatus.FAILED);
            }
        });

        return session;
    }

    public Optional<A2aTaskSession> getTask(String taskId) {
        return Optional.ofNullable(sessions.get(taskId));
    }

    public boolean cancelTask(String taskId, String reason) {
        A2aTaskSession session = sessions.get(taskId);
        if (session != null && session.getStatus() == A2aTaskStatus.WORKING) {
            session.getCancellationToken().cancel(reason);
            session.updateStatus(A2aTaskStatus.CANCELLED);
            return true;
        }
        return false;
    }

    public Flux<A2aTaskEvent> streamEvents(String taskId) {
        A2aTaskSession session = sessions.get(taskId);
        if (session == null) {
            return Flux.error(new IllegalArgumentException("Task not found: " + taskId));
        }
        return session.getEvents();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=A2aTaskManagerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```powershell
git add copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/a2a/manager/
git add copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/a2a/manager/
git commit -m "feat(a2a): implement A2aTaskManager for asynchronous task lifecycle and SSE event dispatch"
```

---

### Task 3: Build Remote A2A Agent Adapter (`RemoteA2aAgent`)

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/a2a/client/RemoteA2aAgent.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/a2a/client/RemoteA2aAgentTest.java`

**Interfaces:**
- Implements: `A2aExecutableAgent`.
- Consumes: Remote A2A Base URL, `AgentCard` (fetched or provided), Spring `WebClient`.
- Produces: `RemoteA2aAgent` which converts `execute(node, input, context)` into remote A2A JSON-RPC calls and maps the returned artifact into local `Artifact<T>`.

- [ ] **Step 1: Write failing test `RemoteA2aAgentTest.java` using MockWebServer**

```java
package com.financial.copilot.agent.core.business.agents.a2a.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aJsonRpcResponse;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.infra.dag.runtime.RunMode;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RemoteA2aAgent 远程客户端适配器测试")
class RemoteA2aAgentTest {

    private MockWebServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    @DisplayName("验证将外部 A2A 服务的 JSON-RPC 调用透明映射为本地 Artifact")
    void testRemoteExecution() throws Exception {
        A2aJsonRpcResponse mockResponse = A2aJsonRpcResponse.success("rpc-1", Map.of("data", "remote-stock-analysis-report"));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(mapper.writeValueAsString(mockResponse)));

        String baseUrl = server.url("/api/a2a").toString();
        RemoteA2aAgent remoteAgent = new RemoteA2aAgent(
                AgentCardDefinitions.stockAnalyzerCard(),
                baseUrl,
                WebClient.builder().build(),
                mapper
        );

        GraphRunRequest request = new GraphRunRequest("r1", 1L, UUID.randomUUID(), 1L, "s1", "贵州茅台深度分析", false, null, null, RunMode.SYNC);
        NodeExecutionContext context = new NodeExecutionContext(request, "node-stock", new ArtifactStore(), new CancellationToken("node-stock"));
        GraphNode node = GraphNode.builder().nodeId("node-stock").taskType("BATCH_ANALYSIS").params(Map.of("assetCategory", "STOCK")).build();

        Artifact<?> result = remoteAgent.execute(node, NodeInput.empty(), context);
        assertNotNull(result);
        assertTrue(result.payload().toString().contains("remote-stock-analysis-report"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=RemoteA2aAgentTest`
Expected: FAIL (`RemoteA2aAgent` not found)

- [ ] **Step 3: Implement `RemoteA2aAgent`**

```java
package com.financial.copilot.agent.core.business.agents.a2a.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aJsonRpcRequest;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aJsonRpcResponse;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import io.a2a.spec.AgentCard;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * <h1>远程 A2A 外部智能体适配器 (RemoteA2aAgent)</h1>
 * <p>
 * 将外部运行的 A2A 兼容智能体透明接入本地 {@link com.financial.copilot.agent.core.business.agents.a2a.A2aAgentRegistry}。
 * 运行时由 {@link AgentScopeAgentFactory} 关联的外部网络通道驱动。
 * </p>
 *
 * @author FinancialCopilot
 */
public class RemoteA2aAgent implements A2aExecutableAgent {

    private final AgentCard card;
    private final String baseUrl;
    private final WebClient webClient;
    private final ObjectMapper mapper;

    public RemoteA2aAgent(AgentCard card, String baseUrl, WebClient webClient, ObjectMapper mapper) {
        this.card = Objects.requireNonNull(card, "card");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.webClient = webClient != null ? webClient : WebClient.builder().build();
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    @Override
    public AgentCard agentCard() {
        return card;
    }

    @Override
    public Artifact<?> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        String prompt = context.request() != null ? context.request().prompt() : "";
        A2aJsonRpcRequest request = new A2aJsonRpcRequest(
                "2.0",
                UUID.randomUUID().toString(),
                "tasks.create",
                Map.of("prompt", prompt, "params", node.getParams() != null ? node.getParams() : Map.of())
        );

        A2aJsonRpcResponse response = webClient.post()
                .uri(baseUrl.endsWith("/") ? baseUrl + "rpc" : baseUrl + "/rpc")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(A2aJsonRpcResponse.class)
                .timeout(Duration.ofSeconds(30))
                .block();

        if (response == null || response.error() != null) {
            String errorMsg = response != null && response.error() != null ? response.error().message() : "Null response from remote agent";
            throw new IllegalStateException("Remote A2A Agent call failed: " + errorMsg);
        }

        return Artifact.of(
                "remote-art-" + UUID.randomUUID().toString().substring(0, 8),
                ArtifactType.GENERAL,
                node.getNodeId(),
                response.result()
        );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=RemoteA2aAgentTest`
Expected: PASS

- [ ] **Step 5: Commit**

```powershell
git add copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/a2a/client/
git add copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/a2a/client/
git commit -m "feat(a2a): implement RemoteA2aAgent adapter for calling external A2A agents"
```

---

### Task 4: Implement A2A Server Controller & WebFlux Routing

**Files:**
- Create: `copilot-app/src/main/java/com/financial/copilot/controller/a2a/A2aServerController.java`
- Modify: `copilot-app/src/main/java/com/financial/copilot/config/security/WebFluxSecurityConfig.java:65-82`
- Test: `copilot-app/src/test/java/com/financial/copilot/controller/a2a/A2aServerControllerTest.java`

**Interfaces:**
- Endpoints:
  - `GET /.well-known/agent.json`: returns default supervisor / summary `AgentCard`.
  - `GET /api/v1/a2a/agents`: returns list of all available `AgentCard`s.
  - `GET /api/v1/a2a/agents/{agentName}/.well-known/agent.json`: returns specific `AgentCard`.
  - `POST /api/v1/a2a/agents/{agentName}/rpc`: processes `tasks.create`, `tasks.cancel`, `tasks.get_task`.
  - `GET /api/v1/a2a/tasks/{taskId}/events` (produces `text/event-stream`): SSE event stream.

- [ ] **Step 1: Write failing controller test `A2aServerControllerTest.java`**

```java
package com.financial.copilot.controller.a2a;

import com.financial.copilot.agent.core.business.agents.a2a.A2aAgentRegistry;
import com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions;
import com.financial.copilot.agent.core.business.agents.a2a.manager.A2aTaskManager;
import com.financial.copilot.agent.core.business.agents.a2a.manager.A2aTaskSession;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aJsonRpcRequest;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskEvent;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;

@WebFluxTest(controllers = A2aServerController.class)
@DisplayName("A2aServerController WebFlux 接口测试")
class A2aServerControllerTest {

    @Autowired
    private WebTestClient webClient;

    @MockBean
    private A2aAgentRegistry agentRegistry;

    @MockBean
    private A2aTaskManager taskManager;

    @Test
    @DisplayName("GET /.well-known/agent.json 自动返回主 AgentCard")
    void testGetWellKnownAgentCard() {
        when(agentRegistry.getAllAgentCards()).thenReturn(List.of(AgentCardDefinitions.fundScreenerCard()));

        webClient.get().uri("/.well-known/agent.json")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.name").isEqualTo("FundScreenerAgent");
    }

    @Test
    @DisplayName("POST /api/v1/a2a/agents/{name}/rpc 成功提交任务")
    void testCreateTaskRpc() {
        A2aTaskSession session = new A2aTaskSession("task-123", "FundScreenerAgent", Map.of("prompt", "测试"), 1L);
        when(taskManager.createTask(eq("FundScreenerAgent"), any(), any())).thenReturn(session);

        A2aJsonRpcRequest request = new A2aJsonRpcRequest("2.0", "1", "tasks.create", Map.of("prompt", "测试"));

        webClient.post().uri("/api/v1/a2a/agents/FundScreenerAgent/rpc")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.taskId").isEqualTo("task-123");
    }

    @Test
    @DisplayName("GET /api/v1/a2a/tasks/{taskId}/events 支持 SSE 事件流")
    void testStreamTaskEvents() {
        when(taskManager.streamEvents("task-123")).thenReturn(
                Flux.just(A2aTaskEvent.status("task-123", A2aTaskStatus.WORKING))
        );

        webClient.get().uri("/api/v1/a2a/tasks/task-123/events")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -s maven-settings.xml -pl copilot-app test -Dtest=A2aServerControllerTest`
Expected: FAIL (`A2aServerController` not found)

- [ ] **Step 3: Implement `A2aServerController` and update `WebFluxSecurityConfig`**

Write `A2aServerController.java`:
```java
package com.financial.copilot.controller.a2a;

import com.financial.copilot.agent.core.business.agents.a2a.A2aAgentRegistry;
import com.financial.copilot.agent.core.business.agents.a2a.manager.A2aTaskManager;
import com.financial.copilot.agent.core.business.agents.a2a.manager.A2aTaskSession;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aJsonRpcRequest;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aJsonRpcResponse;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskEvent;
import com.financial.copilot.security.SecurityUtils;
import io.a2a.spec.AgentCard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <h1>A2A 标准协议服务端控制器 (A2aServerController)</h1>
 * <p>
 * 提供标准 AgentCard 自发现 (.well-known/agent.json)、JSON-RPC 2.0 任务调度及 SSE 事件流输出。
 * </p>
 *
 * @author FinancialCopilot
 */
@RestController
public class A2aServerController {

    private final A2aAgentRegistry registry;
    private final A2aTaskManager taskManager;

    @Autowired
    public A2aServerController(A2aAgentRegistry registry, A2aTaskManager taskManager) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
    }

    @GetMapping(value = "/.well-known/agent.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<AgentCard>> getRootAgentCard() {
        List<AgentCard> cards = registry.getAllAgentCards();
        if (cards.isEmpty()) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        return Mono.just(ResponseEntity.ok(cards.getFirst()));
    }

    @GetMapping(value = "/api/v1/a2a/agents", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<AgentCard>> listAgents() {
        return Mono.just(registry.getAllAgentCards());
    }

    @GetMapping(value = "/api/v1/a2a/agents/{agentName}/.well-known/agent.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<AgentCard>> getAgentCard(@PathVariable String agentName) {
        return Mono.justOrEmpty(registry.findCardByName(agentName))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping(value = "/api/v1/a2a/agents/{agentName}/rpc", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<A2aJsonRpcResponse> handleRpc(
            @PathVariable String agentName,
            @RequestBody A2aJsonRpcRequest request
    ) {
        if (request == null || request.method() == null) {
            return Mono.just(A2aJsonRpcResponse.error(null, -32600, "Invalid Request"));
        }

        return SecurityUtils.currentUserId().defaultIfEmpty(100L).flatMap(userId -> {
            switch (request.method()) {
                case "tasks.create" -> {
                    try {
                        A2aTaskSession session = taskManager.createTask(agentName, request.params(), userId);
                        return Mono.just(A2aJsonRpcResponse.success(request.id(), Map.of(
                                "taskId", session.getTaskId(),
                                "status", session.getStatus().name(),
                                "eventsUrl", "/api/v1/a2a/tasks/" + session.getTaskId() + "/events"
                        )));
                    } catch (Exception e) {
                        return Mono.just(A2aJsonRpcResponse.error(request.id(), -32000, e.getMessage()));
                    }
                }
                case "tasks.cancel" -> {
                    String taskId = request.params() != null ? String.valueOf(request.params().get("taskId")) : null;
                    boolean cancelled = taskId != null && taskManager.cancelTask(taskId, "Cancelled via JSON-RPC");
                    return Mono.just(A2aJsonRpcResponse.success(request.id(), Map.of("cancelled", cancelled)));
                }
                case "tasks.get_task" -> {
                    String taskId = request.params() != null ? String.valueOf(request.params().get("taskId")) : null;
                    return Mono.justOrEmpty(taskId != null ? taskManager.getTask(taskId) : null)
                            .map(s -> A2aJsonRpcResponse.success(request.id(), Map.of(
                                    "taskId", s.getTaskId(),
                                    "status", s.getStatus().name(),
                                    "result", s.getArtifactResult() != null ? s.getArtifactResult() : Map.of()
                            )))
                            .defaultIfEmpty(A2aJsonRpcResponse.error(request.id(), -32004, "Task not found: " + taskId));
                }
                default -> {
                    return Mono.just(A2aJsonRpcResponse.error(request.id(), -32601, "Method not found: " + request.method()));
                }
            }
        });
    }

    @GetMapping(value = "/api/v1/a2a/tasks/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<A2aTaskEvent> streamTaskEvents(@PathVariable String taskId) {
        return taskManager.streamEvents(taskId);
    }
}
```

Update `WebFluxSecurityConfig.java`:
Permit `/.well-known/**` and `/api/v1/a2a/**` for public discovery and RPC.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -s maven-settings.xml -pl copilot-app test -Dtest=A2aServerControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```powershell
git add copilot-app/src/main/java/com/financial/copilot/controller/a2a/
git add copilot-app/src/main/java/com/financial/copilot/config/security/WebFluxSecurityConfig.java
git add copilot-app/src/test/java/com/financial/copilot/controller/a2a/
git commit -m "feat(a2a): add A2aServerController for AgentCard discovery, JSON-RPC, and SSE event streaming"
```

---

### Task 5: End-to-End Multi-Module Verification & Cleanup

- [ ] **Step 1: Run complete `copilot-agent-core` test suite**

Run: `mvn -s maven-settings.xml -pl copilot-agent-core test`
Expected: PASS across all 170+ tests.

- [ ] **Step 2: Run complete `copilot-app` test suite**

Run: `mvn -s maven-settings.xml -pl copilot-app test`
Expected: PASS across all 56+ tests.

- [ ] **Step 3: Check git status and prepare report**

Run: `git status`
Expected: Clean working tree on `feat/a2a-protocol-upgrade`.
