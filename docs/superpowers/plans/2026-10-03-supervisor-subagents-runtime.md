# Supervisor-Subagents Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the financial research execution pipeline into an explicit `Supervisor → Planner → Scheduler → Subagents → Validator → Reviewer → Aggregator` closed loop with bounded retry, replanning, and degradation, while preserving `FinancialResearchWorkflow.run(...)` as the sole entry point and ensuring lease safety and single-write event durability.

**Architecture:** Introduce `SupervisorWorkflow` and `SupervisorStateMachine` to orchestrate research execution. Constrain `GraphPlannerAgent` to schema-validated `TaskDAG` outputs verified by deterministic `PlanValidator`. Split `NodeQualityGate` into deterministic `DeterministicArtifactValidator` and read-only `ReviewerAgent` emitting `ReviewDecision` (PASS, RETRY_TASK, REPLAN, DEGRADE). Constrain `ReportSynthesizerAgent` as `AggregatorAgent` with transparent degradation disclosure. Fix PostgreSQL lease lifecycle (claim -> renew -> complete) and eliminate event double-writing.

**Tech Stack:** Java 21, Spring Boot 3.3.3, WebFlux, AgentScope Java 2.0.0, PostgreSQL 16, MyBatis-Plus 3.5.7, Lombok, Reactor, JUnit 5, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-10-03-supervisor-subagents-runtime-design.md`

## Global Constraints

- Keep each change within the narrowest layer that owns the behavior per `AGENTS.md`.
- Preserve the dependency direction: domain and math remain independent of Spring, MyBatis, and transport concerns.
- Preserve `FinancialResearchWorkflow.run(GraphRunRequest)` as the sole research entry point used by JSON and SSE endpoints, cancellation, and resumption.
- Keep tool output as the source of truth for financial metrics; do not introduce model-derived arithmetic for returns, drawdown, Sharpe, Calmar, or valuation metrics.
- Keep `ExecutionIdentity` (tenantId, userId, conversationId, runId) derived by the server; the LLM must never specify or override identity, resource permissions, or billing amounts.
- Maintain role-scoped immutable toolkits; `ReviewerAgent` must be strictly read-only and have no write tools or report synthesis duties.
- Supervisor loop must be bounded: enforce `maxReviewCycles` (default 3), per-node `maxAttempts` (default 3), total run timeout, and token/point budget. Exhaustion must force `DEGRADE` or clear failure, strictly preventing infinite loops.
- Single-write event persistence: events must be persisted to PostgreSQL exactly once across the outer queued bus and the inner DAG execution bus.
- PostgreSQL node leases must strictly follow `claim` -> `renew` -> `complete`; worker takeover must not rely on JVM memory queues.
- Follow coding standards: Lombok usage, Chinese Javadoc on all public types/methods, explicit imports at top of files without fully qualified names in signatures/bodies.
- Unit tests must run without requiring live PostgreSQL, Redis, Neo4j, or Ollama (`-Dcopilot.neo4j.enabled=false`).
- Working-tree discipline: preserve unrelated working-tree modifications; do not commit, reset, or discard unless explicitly authorized.

---

### Task 1: Supervisor State Machine, Review Decision Contract, and Budget Limits

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/SupervisorState.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/ReviewAction.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/ReviewDecision.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/SupervisorBudget.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/SupervisorStateMachine.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/supervisor/SupervisorStateMachineTest.java`

**Interfaces:**
- Consumes: None (pure domain/infra state constructs)
- Produces:
  - `SupervisorState`: enum (`PLANNING`, `EXECUTING`, `VALIDATING`, `REVIEWING`, `AGGREGATING`, `SUCCEEDED`, `FAILED`, `DEGRADED`)
  - `ReviewAction`: enum (`PASS`, `RETRY_TASK`, `REPLAN`, `DEGRADE`)
  - `ReviewDecision`: record `(ReviewAction action, List<String> targetTaskIds, List<String> issues, List<String> requiredEvidence, double confidence)`
  - `SupervisorBudget`: record `(int maxReviewCycles, int maxNodeAttempts, Duration timeout, long pointBudget)`
  - `SupervisorStateMachine`: state machine managing transitions, cycle counts, per-node retry quotas, and budget exhaustion

- [ ] **Step 1: Write the failing unit tests for SupervisorStateMachine**

Create `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/supervisor/SupervisorStateMachineTest.java`:

```java
package com.financial.copilot.agent.core.infra.supervisor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Supervisor 状态机与审查闭环单元测试")
class SupervisorStateMachineTest {

    @Test
    @DisplayName("正常全流程：PLANNING -> EXECUTING -> VALIDATING -> REVIEWING -> PASS -> AGGREGATING -> SUCCEEDED")
    void testNormalLifecyclePass() {
        SupervisorBudget budget = SupervisorBudget.defaults();
        SupervisorStateMachine sm = new SupervisorStateMachine(budget);

        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.PLANNING);

        sm.transitionTo(SupervisorState.EXECUTING);
        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.EXECUTING);

        sm.transitionTo(SupervisorState.VALIDATING);
        sm.transitionTo(SupervisorState.REVIEWING);

        ReviewDecision decision = ReviewDecision.pass();
        sm.evaluateReview(decision);

        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.AGGREGATING);
        assertThat(sm.isDegraded()).isFalse();

        sm.markSucceeded();
        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.SUCCEEDED);
        assertThat(sm.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("目标任务重试：REVIEWING -> RETRY_TASK -> 回到 EXECUTING，递增审查周期与节点重试次数")
    void testRetryTaskWithinBudget() {
        SupervisorBudget budget = new SupervisorBudget(2, 2, Duration.ofMinutes(1), 1000L);
        SupervisorStateMachine sm = new SupervisorStateMachine(budget);

        sm.transitionTo(SupervisorState.EXECUTING);
        sm.transitionTo(SupervisorState.VALIDATING);
        sm.transitionTo(SupervisorState.REVIEWING);

        ReviewDecision retryDecision = ReviewDecision.retry(List.of("node-1"), "数据日期滞后");
        sm.evaluateReview(retryDecision);

        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.EXECUTING);
        assertThat(sm.getReviewCycle()).isEqualTo(1);
        assertThat(sm.getNodeAttempts("node-1")).isEqualTo(1);

        // 第二次 review 再次 retry 相同节点，超过节点单任务最大重试限制，强制转为降级聚合
        sm.transitionTo(SupervisorState.VALIDATING);
        sm.transitionTo(SupervisorState.REVIEWING);

        sm.recordNodeAttempt("node-1"); // attempt 达到 2
        sm.evaluateReview(retryDecision);

        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.AGGREGATING);
        assertThat(sm.isDegraded()).isTrue();
        assertThat(sm.getDegradationReasons()).contains("数据日期滞后");
    }

    @Test
    @DisplayName("局部重规划：REVIEWING -> REPLAN -> 回到 EXECUTING，超过 maxReviewCycles 强制降级")
    void testReplanExceedsMaxCyclesForcesDegrade() {
        SupervisorBudget budget = new SupervisorBudget(1, 2, Duration.ofMinutes(1), 1000L);
        SupervisorStateMachine sm = new SupervisorStateMachine(budget);

        sm.transitionTo(SupervisorState.EXECUTING);
        sm.transitionTo(SupervisorState.VALIDATING);
        sm.transitionTo(SupervisorState.REVIEWING);

        ReviewDecision replanDecision = ReviewDecision.replan("缺失行业比较", List.of("industry_metric"));
        sm.evaluateReview(replanDecision);

        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.EXECUTING);
        assertThat(sm.getReviewCycle()).isEqualTo(1);

        sm.transitionTo(SupervisorState.VALIDATING);
        sm.transitionTo(SupervisorState.REVIEWING);

        // 已经用完 1 次 cycle，再次 replan 强制降级并进入聚合
        sm.evaluateReview(replanDecision);
        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.AGGREGATING);
        assertThat(sm.isDegraded()).isTrue();
    }

    @Test
    @DisplayName("直接 DEGRADE 决策：进入 AGGREGATING 且标记降级")
    void testDirectDegradeDecision() {
        SupervisorBudget budget = SupervisorBudget.defaults();
        SupervisorStateMachine sm = new SupervisorStateMachine(budget);

        sm.transitionTo(SupervisorState.EXECUTING);
        sm.transitionTo(SupervisorState.VALIDATING);
        sm.transitionTo(SupervisorState.REVIEWING);

        ReviewDecision degradeDecision = ReviewDecision.degrade("宏观数据缺失", List.of("缺少利率走势"));
        sm.evaluateReview(degradeDecision);

        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.AGGREGATING);
        assertThat(sm.isDegraded()).isTrue();
        assertThat(sm.getDegradationReasons()).contains("缺少利率走势");

        sm.markSucceeded();
        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.DEGRADED);
        assertThat(sm.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("非法状态跃迁保护")
    void testInvalidStateTransitions() {
        SupervisorBudget budget = SupervisorBudget.defaults();
        SupervisorStateMachine sm = new SupervisorStateMachine(budget);

        assertThatThrownBy(() -> sm.transitionTo(SupervisorState.REVIEWING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot transition");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```powershell
mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=SupervisorStateMachineTest "-Dcopilot.neo4j.enabled=false"
```
Expected: FAIL with compilation error (classes not found).

- [ ] **Step 3: Write minimal implementation**

Create `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/SupervisorState.java`:
```java
package com.financial.copilot.agent.core.infra.supervisor;

/**
 * <h1>Supervisor 总体运行状态枚举</h1>
 */
public enum SupervisorState {
    PLANNING,
    EXECUTING,
    VALIDATING,
    REVIEWING,
    AGGREGATING,
    SUCCEEDED,
    FAILED,
    DEGRADED
}
```

Create `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/ReviewAction.java`:
```java
package com.financial.copilot.agent.core.infra.supervisor;

/**
 * <h1>审查决策动作枚举</h1>
 */
public enum ReviewAction {
    PASS,
    RETRY_TASK,
    REPLAN,
    DEGRADE
}
```

Create `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/ReviewDecision.java`:
```java
package com.financial.copilot.agent.core.infra.supervisor;

import java.util.List;

/**
 * <h1>ReviewerAgent 审查裁决结构体</h1>
 *
 * @param action 审查动作 (PASS, RETRY_TASK, REPLAN, DEGRADE)
 * @param targetTaskIds 目标重试或变更的任务 ID 列表
 * @param issues 发现的问题或不一致性列表
 * @param requiredEvidence 建议补充的证据或指标 URI 列表
 * @param confidence 审查置信度 (0.0 ~ 1.0)
 */
public record ReviewDecision(
        ReviewAction action,
        List<String> targetTaskIds,
        List<String> issues,
        List<String> requiredEvidence,
        double confidence
) {
    public ReviewDecision {
        targetTaskIds = targetTaskIds != null ? List.copyOf(targetTaskIds) : List.of();
        issues = issues != null ? List.copyOf(issues) : List.of();
        requiredEvidence = requiredEvidence != null ? List.copyOf(requiredEvidence) : List.of();
    }

    public static ReviewDecision pass() {
        return new ReviewDecision(ReviewAction.PASS, List.of(), List.of(), List.of(), 1.0);
    }

    public static ReviewDecision retry(List<String> taskIds, String issue) {
        return new ReviewDecision(ReviewAction.RETRY_TASK, taskIds, List.of(issue), List.of(), 0.8);
    }

    public static ReviewDecision replan(String issue, List<String> requiredEvidence) {
        return new ReviewDecision(ReviewAction.REPLAN, List.of(), List.of(issue), requiredEvidence, 0.7);
    }

    public static ReviewDecision degrade(String reason, List<String> issues) {
        return new ReviewDecision(ReviewAction.DEGRADE, List.of(), issues != null ? issues : List.of(reason), List.of(), 0.5);
    }
}
```

Create `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/SupervisorBudget.java`:
```java
package com.financial.copilot.agent.core.infra.supervisor;

import java.time.Duration;

/**
 * <h1>Supervisor 有界执行预算规格</h1>
 *
 * @param maxReviewCycles 最大审查/重试/重规划循环轮数
 * @param maxNodeAttempts 节点最大尝试次数
 * @param timeout 总运行超时时长
 * @param pointBudget 最大算力积分预算
 */
public record SupervisorBudget(
        int maxReviewCycles,
        int maxNodeAttempts,
        Duration timeout,
        long pointBudget
) {
    public static SupervisorBudget defaults() {
        return new SupervisorBudget(3, 3, Duration.ofMinutes(5), 10_000L);
    }
}
```

Create `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/SupervisorStateMachine.java`:
```java
package com.financial.copilot.agent.core.infra.supervisor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>Supervisor 确定性状态机与闭环控制器</h1>
 * <p>
 * 严格控制 PLANNING → EXECUTING → VALIDATING → REVIEWING → AGGREGATING → TERMINAL 状态迁移。
 * 防止审查死循环，追踪重试配额与降级原因。
 * </p>
 */
public class SupervisorStateMachine {

    private final SupervisorBudget budget;
    private volatile SupervisorState currentState;
    private int reviewCycle = 0;
    private boolean degraded = false;
    private final List<String> degradationReasons = new ArrayList<>();
    private final Map<String, Integer> nodeAttemptCounters = new ConcurrentHashMap<>();

    public SupervisorStateMachine(SupervisorBudget budget) {
        this.budget = budget != null ? budget : SupervisorBudget.defaults();
        this.currentState = SupervisorState.PLANNING;
    }

    public SupervisorState getCurrentState() {
        return currentState;
    }

    public int getReviewCycle() {
        return reviewCycle;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public List<String> getDegradationReasons() {
        return Collections.unmodifiableList(degradationReasons);
    }

    public int getNodeAttempts(String nodeId) {
        return nodeAttemptCounters.getOrDefault(nodeId, 0);
    }

    public void recordNodeAttempt(String nodeId) {
        nodeAttemptCounters.merge(nodeId, 1, Integer::sum);
    }

    public synchronized void transitionTo(SupervisorState nextState) {
        Objects.requireNonNull(nextState, "nextState cannot be null");
        validateTransition(currentState, nextState);
        this.currentState = nextState;
    }

    public synchronized void evaluateReview(ReviewDecision decision) {
        if (currentState != SupervisorState.REVIEWING) {
            throw new IllegalStateException("evaluateReview can only be called in REVIEWING state, currently: " + currentState);
        }
        Objects.requireNonNull(decision, "decision cannot be null");

        switch (decision.action()) {
            case PASS -> {
                this.currentState = SupervisorState.AGGREGATING;
            }
            case RETRY_TASK -> {
                boolean canRetry = reviewCycle < budget.maxReviewCycles();
                for (String taskId : decision.targetTaskIds()) {
                    if (getNodeAttempts(taskId) >= budget.maxNodeAttempts()) {
                        canRetry = false;
                        break;
                    }
                }
                if (canRetry) {
                    reviewCycle++;
                    for (String taskId : decision.targetTaskIds()) {
                        recordNodeAttempt(taskId);
                    }
                    this.currentState = SupervisorState.EXECUTING;
                } else {
                    this.degraded = true;
                    this.degradationReasons.addAll(decision.issues());
                    if (this.degradationReasons.isEmpty()) {
                        this.degradationReasons.add("Retry attempts or cycles exhausted");
                    }
                    this.currentState = SupervisorState.AGGREGATING;
                }
            }
            case REPLAN -> {
                if (reviewCycle < budget.maxReviewCycles()) {
                    reviewCycle++;
                    this.currentState = SupervisorState.EXECUTING;
                } else {
                    this.degraded = true;
                    this.degradationReasons.addAll(decision.issues());
                    if (this.degradationReasons.isEmpty()) {
                        this.degradationReasons.add("Replan cycles exhausted");
                    }
                    this.currentState = SupervisorState.AGGREGATING;
                }
            }
            case DEGRADE -> {
                this.degraded = true;
                this.degradationReasons.addAll(decision.issues());
                this.currentState = SupervisorState.AGGREGATING;
            }
        }
    }

    public synchronized void markSucceeded() {
        if (currentState != SupervisorState.AGGREGATING) {
            throw new IllegalStateException("markSucceeded requires AGGREGATING state, currently: " + currentState);
        }
        this.currentState = degraded ? SupervisorState.DEGRADED : SupervisorState.SUCCEEDED;
    }

    public synchronized void markFailed(String reason) {
        this.degraded = true;
        if (reason != null && !reason.isBlank()) {
            this.degradationReasons.add(reason);
        }
        this.currentState = SupervisorState.FAILED;
    }

    public boolean isTerminal() {
        return currentState == SupervisorState.SUCCEEDED
                || currentState == SupervisorState.DEGRADED
                || currentState == SupervisorState.FAILED;
    }

    private void validateTransition(SupervisorState from, SupervisorState to) {
        if (from == to) return;
        boolean valid = switch (from) {
            case PLANNING -> to == SupervisorState.EXECUTING || to == SupervisorState.FAILED;
            case EXECUTING -> to == SupervisorState.VALIDATING || to == SupervisorState.FAILED;
            case VALIDATING -> to == SupervisorState.REVIEWING || to == SupervisorState.EXECUTING || to == SupervisorState.FAILED;
            case REVIEWING -> to == SupervisorState.AGGREGATING || to == SupervisorState.EXECUTING || to == SupervisorState.FAILED;
            case AGGREGATING -> to == SupervisorState.SUCCEEDED || to == SupervisorState.DEGRADED || to == SupervisorState.FAILED;
            case SUCCEEDED, DEGRADED, FAILED -> false;
        };
        if (!valid) {
            throw new IllegalStateException("Cannot transition from " + from + " to " + to);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```powershell
mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=SupervisorStateMachineTest "-Dcopilot.neo4j.enabled=false"
```
Expected: PASS with 5 tests succeeded.

- [ ] **Step 5: Preserve changes**
Verify `git status` shows untracked/modified files are strictly those in Task 1.

---

### Task 2: TaskDAG Contract and Deterministic PlanValidator

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/validator/PlanValidationResult.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/validator/PlanValidator.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/planner/GraphPlannerAgent.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/dag/validator/PlanValidatorTest.java`

**Interfaces:**
- Consumes: `ExecutionGraph`, `AgentRoleCatalog`, `RuntimeCapacityProperties`
- Produces: `PlanValidator.validate(ExecutionGraph graph)` returning `PlanValidationResult`

- [ ] **Step 1: Write the failing unit tests for PlanValidator**

Create `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/dag/validator/PlanValidatorTest.java`:

```java
package com.financial.copilot.agent.core.infra.dag.validator;

import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("确定性执行图规划校验器 (PlanValidator) 测试")
class PlanValidatorTest {

    private final PlanValidator validator = new PlanValidator();

    @Test
    @DisplayName("合法图结构校验通过")
    void testValidGraphPasses() {
        ExecutionGraph graph = new ExecutionGraph("g1");
        GraphNode n1 = new GraphNode("n1", "筛选", "SCREENING", Map.of(), Duration.ofSeconds(10), 1, 0, null, null, null);
        GraphNode n2 = new GraphNode("n2", "分析", "BATCH_ANALYSIS", Map.of(), Duration.ofSeconds(10), 1, 0, null, null, null);
        n2.setInputBindings(Map.of("candidates", "n1"));

        graph.addNode(n1);
        graph.addNode(n2);
        graph.addEdge("n1", "n2");

        PlanValidationResult result = validator.validate(graph);
        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    @DisplayName("拒绝包含环的非法图")
    void testRejectCyclicGraph() {
        ExecutionGraph graph = new ExecutionGraph("g_cycle");
        GraphNode n1 = new GraphNode("n1", "n1", "SCREENING", Map.of(), Duration.ofSeconds(10), 1, 0, null, null, null);
        GraphNode n2 = new GraphNode("n2", "n2", "BATCH_ANALYSIS", Map.of(), Duration.ofSeconds(10), 1, 0, null, null, null);
        n1.setInputBindings(Map.of("in", "n2"));
        n2.setInputBindings(Map.of("in", "n1"));

        graph.addNode(n1);
        graph.addNode(n2);
        graph.addEdge("n1", "n2");
        graph.addEdge("n2", "n1");

        PlanValidationResult result = validator.validate(graph);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("Cycle"));
    }

    @Test
    @DisplayName("拒绝未注册角色的非法节点")
    void testRejectUnregisteredRole() {
        ExecutionGraph graph = new ExecutionGraph("g_bad_role");
        GraphNode n1 = new GraphNode("n1", "hacker", "ARBITRARY_SHELL_EXEC", Map.of(), Duration.ofSeconds(10), 1, 0, null, null, null);
        graph.addNode(n1);

        PlanValidationResult result = validator.validate(graph);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("unregistered taskType"));
    }

    @Test
    @DisplayName("拒绝非根节点缺失 inputBindings")
    void testRejectMissingInputBindings() {
        ExecutionGraph graph = new ExecutionGraph("g_missing_binding");
        GraphNode n1 = new GraphNode("n1", "root", "SCREENING", Map.of(), Duration.ofSeconds(10), 1, 0, null, null, null);
        GraphNode n2 = new GraphNode("n2", "child", "BATCH_ANALYSIS", Map.of(), Duration.ofSeconds(10), 1, 0, null, null, null);
        // n2 没有设置 inputBindings
        graph.addNode(n1);
        graph.addNode(n2);
        graph.addEdge("n1", "n2");

        PlanValidationResult result = validator.validate(graph);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("inputBindings"));
    }

    @Test
    @DisplayName("拒绝模型试图注入受保护字段 (userId, tenantId, runId)")
    void testRejectProtectedFieldInjection() {
        ExecutionGraph graph = new ExecutionGraph("g_spoof");
        GraphNode n1 = new GraphNode("n1", "spoof", "SCREENING", Map.of("userId", 99999L, "tenantId", "evil"),
                Duration.ofSeconds(10), 1, 0, null, null, null);
        graph.addNode(n1);

        PlanValidationResult result = validator.validate(graph);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("protected parameter"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```powershell
mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=PlanValidatorTest "-Dcopilot.neo4j.enabled=false"
```
Expected: FAIL with compilation error (classes not found).

- [ ] **Step 3: Write minimal implementation**

Create `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/validator/PlanValidationResult.java`:
```java
package com.financial.copilot.agent.core.infra.dag.validator;

import java.util.Collections;
import java.util.List;

/**
 * <h1>图规划确定性校验结果</h1>
 */
public class PlanValidationResult {

    private final boolean valid;
    private final List<String> errors;

    private PlanValidationResult(boolean valid, List<String> errors) {
        this.valid = valid;
        this.errors = errors != null ? List.copyOf(errors) : List.of();
    }

    public static PlanValidationResult success() {
        return new PlanValidationResult(true, List.of());
    }

    public static PlanValidationResult failure(List<String> errors) {
        return new PlanValidationResult(false, errors);
    }

    public boolean isValid() {
        return valid;
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }
}
```

Create `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/validator/PlanValidator.java`:
```java
package com.financial.copilot.agent.core.infra.dag.validator;

import com.financial.copilot.agent.core.business.agents.AgentRoleCatalog;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <h1>确定性执行图规划校验器 (PlanValidator)</h1>
 * <p>
 * 保证模型生成的 TaskDAG 映射至 ExecutionGraph 时符合严格的安全与结构约束：
 * 拒绝成环、拒绝未注册角色、拒绝非根节点缺失输入绑定、拒绝注入保护字段（userId、tenantId、runId、计费金额）。
 * </p>
 */
@Component
public class PlanValidator {

    private static final Set<String> PROTECTED_PARAMS = Set.of(
            "userid", "user_id", "tenantid", "tenant_id", "runid", "run_id", "points", "cost", "billing"
    );

    public PlanValidationResult validate(ExecutionGraph graph) {
        if (graph == null || graph.getNodes().isEmpty()) {
            return PlanValidationResult.failure(List.of("ExecutionGraph is null or contains no nodes"));
        }

        List<String> errors = new ArrayList<>();

        if (graph.hasCycle()) {
            errors.add("Cycle detected in ExecutionGraph: " + graph.getGraphId());
        }

        for (GraphNode node : graph.getNodes().values()) {
            if (!AgentRoleCatalog.supports(node.getTaskType())) {
                errors.add("Node [" + node.getNodeId() + "] has unregistered taskType: " + node.getTaskType());
            }

            Set<String> upstreams = graph.getUpstream(node.getNodeId());
            if (!upstreams.isEmpty() && (node.getInputBindings() == null || node.getInputBindings().isEmpty())) {
                errors.add("Non-root node [" + node.getNodeId() + "] requires explicit inputBindings");
            }

            if (node.getParams() != null) {
                for (String key : node.getParams().keySet()) {
                    if (key != null && PROTECTED_PARAMS.contains(key.toLowerCase())) {
                        errors.add("Node [" + node.getNodeId() + "] specifies protected parameter: " + key);
                    }
                }
            }
        }

        if (!errors.isEmpty()) {
            return PlanValidationResult.failure(errors);
        }
        return PlanValidationResult.success();
    }
}
```

Modify `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/planner/GraphPlannerAgent.java`:
Wire `PlanValidator` into `plan` and `planPatch` to reject invalid graphs immediately before returning.

- [ ] **Step 4: Run test to verify it passes**

Run:
```powershell
mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=PlanValidatorTest "-Dcopilot.neo4j.enabled=false"
```
Expected: PASS with 5 tests succeeded.

- [ ] **Step 5: Preserve changes**
Verify `git status` shows clean additions without regressions.

---

### Task 3: Deterministic ArtifactValidator (Splitting NodeQualityGate)

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/validator/ArtifactValidationResult.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/validator/ArtifactValidator.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/validator/DeterministicArtifactValidator.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/guard/DefaultNodeQualityGate.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/dag/validator/DeterministicArtifactValidatorTest.java`

**Interfaces:**
- Consumes: `Artifact<?>`, `GraphNode`, `ExecutionGraph`
- Produces: `ArtifactValidator.validate(GraphNode node, Artifact<?> artifact, ExecutionGraph graph)` returning `ArtifactValidationResult`

- [ ] **Step 1: Write the failing unit tests for DeterministicArtifactValidator**

Create `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/dag/validator/DeterministicArtifactValidatorTest.java`:

```java
package com.financial.copilot.agent.core.infra.dag.validator;

import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactMetadata;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.artifact.EvidenceContract;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("确定性产物校验器 (DeterministicArtifactValidator) 测试")
class DeterministicArtifactValidatorTest {

    private final DeterministicArtifactValidator validator = new DeterministicArtifactValidator();
    private final ExecutionGraph graph = new ExecutionGraph("g1");
    private final GraphNode node = new GraphNode("node-1", "分析", "BATCH_ANALYSIS", Map.of(), Duration.ofSeconds(10), 1, 0, null, null, null);

    @Test
    @DisplayName("完整产物校验通过")
    void testValidArtifactPasses() {
        Artifact<String> art = Artifact.of(
                "art-1", ArtifactType.FUND_ANALYSIS, "node-1", "基金分析详情",
                ArtifactMetadata.standard("FundAnalyzer"),
                EvidenceContract.sufficient("分析结论", List.of("fund://000001"))
        );

        ArtifactValidationResult result = validator.validate(node, art, graph);
        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    @DisplayName("空产物或空载荷被拒绝")
    void testNullArtifactOrPayloadRejected() {
        ArtifactValidationResult resNull = validator.validate(node, null, graph);
        assertThat(resNull.isValid()).isFalse();

        Artifact<Object> resNullPayload = new Artifact<>("art-2", ArtifactType.GENERAL, "node-1", null, null, null);
        ArtifactValidationResult resPayload = validator.validate(node, resNullPayload, graph);
        assertThat(resPayload.isValid()).isFalse();
    }

    @Test
    @DisplayName("产物体积超过上限 (500KB) 被拒绝，防止 OOM 与 Token 溢出")
    void testOversizedPayloadRejected() {
        String hugePayload = "A".repeat(600_000);
        Artifact<String> art = Artifact.of("art-huge", ArtifactType.GENERAL, "node-1", hugePayload);

        ArtifactValidationResult result = validator.validate(node, art, graph);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("exceeds max length"));
    }

    @Test
    @DisplayName("置信度过低且为 partial 产物被拒绝")
    void testLowConfidencePartialRejected() {
        EvidenceContract contract = new EvidenceContract("不确定结论", List.of(), List.of(), List.of("missing_data"), 0.3);
        Artifact<String> art = Artifact.of(
                "art-low", ArtifactType.GENERAL, "node-1", "低置信度内容",
                ArtifactMetadata.partial("FundAnalyzer", List.of("000001"), "缺少数据"),
                contract
        );

        ArtifactValidationResult result = validator.validate(node, art, graph);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getMissingEvidence()).contains("missing_data");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```powershell
mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=DeterministicArtifactValidatorTest "-Dcopilot.neo4j.enabled=false"
```
Expected: FAIL with compilation error.

- [ ] **Step 3: Write minimal implementation**

Create `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/validator/ArtifactValidationResult.java`:
```java
package com.financial.copilot.agent.core.infra.dag.validator;

import java.util.Collections;
import java.util.List;

/**
 * <h1>产物确定性校验结果</h1>
 */
public class ArtifactValidationResult {

    private final boolean valid;
    private final List<String> errors;
    private final List<String> missingEvidence;

    private ArtifactValidationResult(boolean valid, List<String> errors, List<String> missingEvidence) {
        this.valid = valid;
        this.errors = errors != null ? List.copyOf(errors) : List.of();
        this.missingEvidence = missingEvidence != null ? List.copyOf(missingEvidence) : List.of();
    }

    public static ArtifactValidationResult success() {
        return new ArtifactValidationResult(true, List.of(), List.of());
    }

    public static ArtifactValidationResult failure(List<String> errors, List<String> missingEvidence) {
        return new ArtifactValidationResult(false, errors, missingEvidence);
    }

    public boolean isValid() {
        return valid;
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    public List<String> getMissingEvidence() {
        return Collections.unmodifiableList(missingEvidence);
    }
}
```

Create `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/validator/ArtifactValidator.java`:
```java
package com.financial.copilot.agent.core.infra.dag.validator;

import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;

/**
 * <h1>产物确定性校验器端口</h1>
 */
public interface ArtifactValidator {
    ArtifactValidationResult validate(GraphNode node, Artifact<?> artifact, ExecutionGraph graph);
}
```

Create `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/validator/DeterministicArtifactValidator.java`:
```java
package com.financial.copilot.agent.core.infra.dag.validator;

import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.EvidenceContract;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <h1>确定性金融产物校验器 (DeterministicArtifactValidator)</h1>
 * <p>
 * 纯 Java 规则执行：Schema 检查、文本尺寸安全边界 (500KB)、必填证据链完整性及置信度底线。
 * 不依赖 LLM 推理。
 * </p>
 */
@Component
public class DeterministicArtifactValidator implements ArtifactValidator {

    private static final int MAX_PAYLOAD_CHARS = 500_000;

    @Override
    public ArtifactValidationResult validate(GraphNode node, Artifact<?> artifact, ExecutionGraph graph) {
        if (artifact == null) {
            return ArtifactValidationResult.failure(List.of("Artifact is null for node: " + (node != null ? node.getNodeId() : "unknown")), List.of());
        }
        if (artifact.payload() == null) {
            return ArtifactValidationResult.failure(List.of("Artifact payload is null for node: " + (node != null ? node.getNodeId() : "unknown")), List.of());
        }

        List<String> errors = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();

        String payloadStr = String.valueOf(artifact.payload());
        if (payloadStr.length() > MAX_PAYLOAD_CHARS) {
            errors.add("Artifact payload length [" + payloadStr.length() + "] exceeds max length " + MAX_PAYLOAD_CHARS);
        }

        EvidenceContract contract = artifact.evidenceContract();
        if (contract != null) {
            if (contract.missingEvidence() != null && !contract.missingEvidence().isEmpty()) {
                missingEvidence.addAll(contract.missingEvidence());
            }
            if (contract.confidence() < 0.60 && artifact.metadata() != null && artifact.metadata().partial()) {
                errors.add("Confidence [" + contract.confidence() + "] too low for partial artifact");
            }
        }

        if (!errors.isEmpty() || !missingEvidence.isEmpty()) {
            return ArtifactValidationResult.failure(errors, missingEvidence);
        }
        return ArtifactValidationResult.success();
    }
}
```

Modify `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/guard/DefaultNodeQualityGate.java` to delegate to `DeterministicArtifactValidator`.

- [ ] **Step 4: Run test to verify it passes**

Run:
```powershell
mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=DeterministicArtifactValidatorTest "-Dcopilot.neo4j.enabled=false"
```
Expected: PASS.

- [ ] **Step 5: Preserve changes**
Verify tests pass and git status is clean.

---

### Task 4: ReviewerAgent and Read-Only Reviewer Toolkit

**Files:**
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/AgentRole.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/prompt/ReviewerPrompt.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/ReviewerAgent.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/agentscope/RoleToolkitRegistry.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/ReviewerAgentTest.java`

**Interfaces:**
- Consumes: `AgentScopeAgentFactory`, `RoleToolkitRegistry`, `ExecutionGraph`, `ArtifactStore`, `NodeExecutionContext`
- Produces: `ReviewerAgent.review(ExecutionGraph graph, ArtifactStore store, NodeExecutionContext context)` returning `ReviewDecision`

- [ ] **Step 1: Write the failing unit tests for ReviewerAgent**

Create `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/ReviewerAgentTest.java`:

```java
package com.financial.copilot.agent.core.business.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.agentscope.RoleToolkitRegistry;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.RunMode;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.infra.supervisor.ReviewAction;
import com.financial.copilot.agent.core.infra.supervisor.ReviewDecision;
import io.agentscope.core.message.Msg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("ReviewerAgent 审查角色单元测试")
class ReviewerAgentTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Reviewer 正常给出 PASS 裁决")
    void testReviewPass() {
        AgentScopeAgentFactory factory = Mockito.mock(AgentScopeAgentFactory.class);
        RoleToolkitRegistry registry = Mockito.mock(RoleToolkitRegistry.class);
        when(registry.toolkitFor(AgentRole.REVIEWER)).thenReturn(new io.agentscope.core.tool.Toolkit());

        Msg mockReply = Msg.builder().textContent("""
                ```json
                {
                    "action": "PASS",
                    "targetTaskIds": [],
                    "issues": [],
                    "requiredEvidence": [],
                    "confidence": 0.95
                }
                ```
                """).build();
        AgentScopeAgentFactory.AgentExecutionTrace trace = new AgentScopeAgentFactory.AgentExecutionTrace(
                mockReply, List.of(new AgentScopeAgentFactory.ToolObservation("read_research_artifacts", "{}")), 100L);
        when(factory.invokeWithTrace(any(), any(), any())).thenReturn(trace);

        ReviewerAgent reviewer = new ReviewerAgent(factory, registry, mapper);

        ExecutionGraph graph = new ExecutionGraph("g1");
        GraphNode n1 = new GraphNode("n1", "分析", "BATCH_ANALYSIS", Map.of(), Duration.ofSeconds(10), 1, 0, null, null, null);
        graph.addNode(n1);

        ArtifactStore store = new ArtifactStore();
        store.store("n1", Artifact.of("a1", ArtifactType.FUND_ANALYSIS, "n1", "有效分析结论"));

        GraphRunRequest req = new GraphRunRequest("r1", 1L, UUID.randomUUID(), "sess", "query", false, null, null, RunMode.SYNC);
        NodeExecutionContext ctx = new NodeExecutionContext(req, "__reviewer__", store, new CancellationToken("r1"));

        ReviewDecision decision = reviewer.review(graph, store, ctx);
        assertThat(decision.action()).isEqualTo(ReviewAction.PASS);
        assertThat(decision.confidence()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("Reviewer 发现数据缺失给出 RETRY_TASK 裁决")
    void testReviewRetryTask() {
        AgentScopeAgentFactory factory = Mockito.mock(AgentScopeAgentFactory.class);
        RoleToolkitRegistry registry = Mockito.mock(RoleToolkitRegistry.class);
        when(registry.toolkitFor(AgentRole.REVIEWER)).thenReturn(new io.agentscope.core.tool.Toolkit());

        Msg mockReply = Msg.builder().textContent("""
                ```json
                {
                    "action": "RETRY_TASK",
                    "targetTaskIds": ["n1"],
                    "issues": ["数据滞后"],
                    "requiredEvidence": ["fund://latest_nav"],
                    "confidence": 0.85
                }
                ```
                """).build();
        AgentScopeAgentFactory.AgentExecutionTrace trace = new AgentScopeAgentFactory.AgentExecutionTrace(
                mockReply, List.of(new AgentScopeAgentFactory.ToolObservation("read_research_artifacts", "{}")), 100L);
        when(factory.invokeWithTrace(any(), any(), any())).thenReturn(trace);

        ReviewerAgent reviewer = new ReviewerAgent(factory, registry, mapper);
        ExecutionGraph graph = new ExecutionGraph("g1");
        ArtifactStore store = new ArtifactStore();
        GraphRunRequest req = new GraphRunRequest("r1", 1L, UUID.randomUUID(), "sess", "query", false, null, null, RunMode.SYNC);
        NodeExecutionContext ctx = new NodeExecutionContext(req, "__reviewer__", store, new CancellationToken("r1"));

        ReviewDecision decision = reviewer.review(graph, store, ctx);
        assertThat(decision.action()).isEqualTo(ReviewAction.RETRY_TASK);
        assertThat(decision.targetTaskIds()).contains("n1");
        assertThat(decision.issues()).contains("数据滞后");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```powershell
mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=ReviewerAgentTest "-Dcopilot.neo4j.enabled=false"
```
Expected: FAIL with compilation error.

- [ ] **Step 3: Write minimal implementation**

Modify `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/AgentRole.java`:
```java
package com.financial.copilot.agent.core.business.agents;

/**
 * <h1>Agent 投研角色枚举</h1>
 * 对应具体的 AgentScope ReAct 角色实现与静态工具包配置。
 */
public enum AgentRole {
    FUND_ANALYZER,
    FUND_SCREENER,
    FUND_COMPARATOR,
    STOCK_ANALYZER,
    STOCK_SCREENER,
    STOCK_COMPARATOR,
    REPORT_SYNTHESIZER,
    REVIEWER
}
```

Create `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/prompt/ReviewerPrompt.java`:
```java
package com.financial.copilot.agent.core.infra.prompt;

/**
 * <h1>ReviewerAgent 审查角色 Prompt 契约</h1>
 */
public class ReviewerPrompt {

    public static final String SYSTEM_PROMPT = """
            你是一个机构级投资研究质量审查与事实核查专家 (ReviewerAgent)。
            职责约束：
            1. 严格审查产物间的跨节点一致性、问题覆盖度、冲突、风险披露以及是否足以回答用户。
            2. 你是只读审查员，严禁调用任何写操作工具，不得篡改原始数据，不得撰写最终研报。
            3. 输出必须是合法的 JSON 对象代码块：
            ```json
            {
              "action": "PASS | RETRY_TASK | REPLAN | DEGRADE",
              "targetTaskIds": ["可重试或有缺陷的任务ID"],
              "issues": ["发现的问题或冲突列表"],
              "requiredEvidence": ["建议补充的证据URI"],
              "confidence": 0.95
            }
            ```
            """;
}
```

Create `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/ReviewerAgent.java`:
```java
package com.financial.copilot.agent.core.business.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.agentscope.RoleToolkitRegistry;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.prompt.ReviewerPrompt;
import com.financial.copilot.agent.core.infra.supervisor.ReviewDecision;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * <h1>投研跨证据质量审查 Agent (ReviewerAgent)</h1>
 * <p>
 * 仅具备只读工具包，审查经 Validator 通过的产物一致性、覆盖度与风险，
 * 输出结构化 ReviewDecision (PASS, RETRY_TASK, REPLAN, DEGRADE)。
 * </p>
 */
@Component
public class ReviewerAgent {

    private final AgentScopeAgentFactory factory;
    private final RoleToolkitRegistry toolkitRegistry;
    private final ObjectMapper mapper;

    @Autowired
    public ReviewerAgent(AgentScopeAgentFactory factory, RoleToolkitRegistry toolkitRegistry, ObjectMapper mapper) {
        this.factory = Objects.requireNonNull(factory, "factory cannot be null");
        this.toolkitRegistry = Objects.requireNonNull(toolkitRegistry, "toolkitRegistry cannot be null");
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public ReviewDecision review(ExecutionGraph graph, ArtifactStore store, NodeExecutionContext context) {
        Toolkit toolkit = toolkitRegistry.toolkitFor(AgentRole.REVIEWER);
        String userPrompt = "审查当前已完成执行图产物质量，并给出 ReviewDecision。\n用户原始问题："
                + context.request().prompt() + "\n图节点状态：" + graph.getNodes().keySet();

        var run = factory.invokeWithTrace(new AgentScopeAgentFactory.AgentDefinition(
                "ReviewerAgent", "跨证据研报审查", ReviewerPrompt.SYSTEM_PROMPT, toolkit, 5),
                userPrompt, context);

        try {
            String text = stripFence(run.reply().getTextContent());
            return mapper.readValue(text, ReviewDecision.class);
        } catch (Exception e) {
            // 解析失败时兜底为降级或安全放行
            return ReviewDecision.degrade("Reviewer response parse failed: " + e.getMessage(), java.util.List.of("JSON parse error"));
        }
    }

    private static String stripFence(String text) {
        return text == null ? "" : text.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
    }
}
```

Update `RoleToolkitRegistry.java` to register `AgentRole.REVIEWER` with read-only `read_research_artifacts` tool.

- [ ] **Step 4: Run test to verify it passes**

Run:
```powershell
mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=ReviewerAgentTest "-Dcopilot.neo4j.enabled=false"
```
Expected: PASS.

- [ ] **Step 5: Preserve changes**
Verify git status is clean.

---

### Task 5: AggregatorAgent Refinement and Degradation Disclosures

**Files:**
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/AggregatorAgent.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/ReportSynthesizerAgent.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/prompt/ReportSynthesizerPrompt.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/ReportSynthesizerAgentTest.java`

**Interfaces:**
- Consumes: `GraphNode`, `NodeInput`, `NodeExecutionContext`, optional `ReviewDecision`
- Produces: `Artifact<FinalSynthesisReport>`

- [ ] **Step 1: Write the failing unit tests for ReportSynthesizer / Aggregator**

Create/update `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/business/agents/ReportSynthesizerAgentTest.java`:

```java
package com.financial.copilot.agent.core.business.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.agentscope.RoleToolkitRegistry;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.infra.dag.runtime.RunMode;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.infra.supervisor.ReviewDecision;
import io.agentscope.core.message.Msg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("AggregatorAgent / ReportSynthesizerAgent 聚合与降级披露测试")
class ReportSynthesizerAgentTest {

    @Test
    @DisplayName("降级场景下合成报告，包含明确的限制披露与数据缺口")
    void testSynthesizeWithDegradation() {
        AgentScopeAgentFactory factory = Mockito.mock(AgentScopeAgentFactory.class);
        RoleToolkitRegistry registry = Mockito.mock(RoleToolkitRegistry.class);
        when(registry.toolkitFor(AgentRole.REPORT_SYNTHESIZER)).thenReturn(new io.agentscope.core.tool.Toolkit());

        Msg mockReply = Msg.builder().textContent("基于部分事实合成的研报内容").build();
        AgentScopeAgentFactory.AgentExecutionTrace trace = new AgentScopeAgentFactory.AgentExecutionTrace(
                mockReply, List.of(new AgentScopeAgentFactory.ToolObservation("read_research_artifacts", "{}")), 100L);
        when(factory.invokeWithTrace(any(), any(), any())).thenReturn(trace);

        ReportSynthesizerAgent synthesizer = new ReportSynthesizerAgent(factory, registry);

        GraphNode node = new GraphNode("synth", "合成", "SYNTHESIS", Map.of(), Duration.ofSeconds(10), 1, 0, null, null, null);
        Artifact<String> art = Artifact.of("a1", ArtifactType.FUND_ANALYSIS, "n1", "基础收益数据");
        NodeInput input = new NodeInput(Map.of("analysis", art));

        ArtifactStore store = new ArtifactStore();
        store.store("n1", art);
        GraphRunRequest req = new GraphRunRequest("r1", 1L, UUID.randomUUID(), "sess", "基金收益分析", false, null, null, RunMode.SYNC);
        NodeExecutionContext ctx = new NodeExecutionContext(req, "synth", store, new CancellationToken("r1"), 1, input);

        ReviewDecision degradeDecision = ReviewDecision.degrade("宏观指标缺失", List.of("利率数据未更新"));

        Artifact<FinalSynthesisReport> result = synthesizer.executeWithReview(node, input, ctx, degradeDecision);

        assertThat(result.type()).isEqualTo(ArtifactType.FINAL_REPORT);
        assertThat(result.payload().summary()).contains("降级披露");
        assertThat(result.payload().content()).contains("基于部分事实合成的研报内容");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```powershell
mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=ReportSynthesizerAgentTest "-Dcopilot.neo4j.enabled=false"
```
Expected: FAIL with method not found `executeWithReview`.

- [ ] **Step 3: Write minimal implementation**

Create `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/AggregatorAgent.java`:
```java
package com.financial.copilot.agent.core.business.agents;

import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.infra.supervisor.ReviewDecision;

/**
 * <h1>投研产物最终聚合器接口 (AggregatorAgent)</h1>
 */
public interface AggregatorAgent {
    Artifact<FinalSynthesisReport> executeWithReview(GraphNode node, NodeInput input, NodeExecutionContext context, ReviewDecision reviewDecision);
}
```

Modify `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/business/agents/ReportSynthesizerAgent.java`:
Implement `AggregatorAgent`. In `executeWithReview`, if `reviewDecision != null && reviewDecision.action() == ReviewAction.DEGRADE`, prefix summary and user prompt with degradation warning and disclosure.

- [ ] **Step 4: Run test to verify it passes**

Run:
```powershell
mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=ReportSynthesizerAgentTest "-Dcopilot.neo4j.enabled=false"
```
Expected: PASS.

- [ ] **Step 5: Preserve changes**
Verify git status is clean.

---

### Task 6: SupervisorWorkflow Implementation with Bounded Loop, Replan, and SSE Events

**Files:**
- Modify: `copilot-common/src/main/java/com/financial/copilot/common/event/ResearchStreamEvent.java`
- Create: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/SupervisorWorkflow.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/supervisor/SupervisorWorkflowTest.java`

**Interfaces:**
- Consumes: `GraphPlannerAgent`, `PlanValidator`, `DagRuntime`, `DeterministicArtifactValidator`, `ReviewerAgent`, `ReportSynthesizerAgent`, `SupervisorBudget`
- Produces: `SupervisorWorkflow.execute(GraphRunRequest request, ExecutionGraph graph, DagRunContext context)` returning `CompletableFuture<GraphRunResult>`

- [ ] **Step 1: Write the failing unit tests for SupervisorWorkflow**

Create `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/supervisor/SupervisorWorkflowTest.java`:

```java
package com.financial.copilot.agent.core.infra.supervisor;

import com.financial.copilot.agent.core.business.agents.ReportSynthesizerAgent;
import com.financial.copilot.agent.core.business.agents.ReviewerAgent;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.planner.GraphPlannerAgent;
import com.financial.copilot.agent.core.infra.dag.runtime.DagRuntime;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunHandle;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunResult;
import com.financial.copilot.agent.core.infra.dag.runtime.RunMode;
import com.financial.copilot.agent.core.infra.dag.validator.ArtifactValidationResult;
import com.financial.copilot.agent.core.infra.dag.validator.DeterministicArtifactValidator;
import com.financial.copilot.agent.core.infra.dag.validator.PlanValidationResult;
import com.financial.copilot.agent.core.infra.dag.validator.PlanValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("SupervisorWorkflow 闭环编排测试")
class SupervisorWorkflowTest {

    @Test
    @DisplayName("完整闭环：Planner 校验通过 -> 执行 DAG -> Validator 校验 -> Reviewer PASS -> 聚合研报")
    void testEndToEndSupervisorPass() throws Exception {
        PlanValidator planValidator = Mockito.mock(PlanValidator.class);
        when(planValidator.validate(any())).thenReturn(PlanValidationResult.success());

        DagRuntime runtime = Mockito.mock(DagRuntime.class);
        ExecutionGraph graph = new ExecutionGraph("g1");
        GraphNode n1 = new GraphNode("n1", "分析", "BATCH_ANALYSIS", Map.of(), Duration.ofSeconds(10), 1, 0, null, null, null);
        graph.addNode(n1);

        Artifact<String> art = Artifact.of("a1", ArtifactType.FUND_ANALYSIS, "n1", "分析事实");
        GraphRunResult initialResult = new GraphRunResult("r1", graph, Map.of("n1", art), Map.of(), 100L);
        GraphRunHandle handle = new GraphRunHandle("r1", Flux.empty(), CompletableFuture.completedFuture(initialResult), () -> {});
        when(runtime.run(any(), any())).thenReturn(handle);

        DeterministicArtifactValidator artifactValidator = Mockito.mock(DeterministicArtifactValidator.class);
        when(artifactValidator.validate(any(), any(), any())).thenReturn(ArtifactValidationResult.success());

        ReviewerAgent reviewer = Mockito.mock(ReviewerAgent.class);
        when(reviewer.review(any(), any(), any())).thenReturn(ReviewDecision.pass());

        ReportSynthesizerAgent aggregator = Mockito.mock(ReportSynthesizerAgent.class);
        FinalSynthesisReport reportPayload = FinalSynthesisReport.of("标题", "摘要", "正文", Map.of(), List.of());
        Artifact<FinalSynthesisReport> reportArt = Artifact.of("rep-1", ArtifactType.FINAL_REPORT, "agg", reportPayload);
        when(aggregator.executeWithReview(any(), any(), any(), any())).thenReturn(reportArt);

        SupervisorWorkflow supervisor = new SupervisorWorkflow(
                planValidator, runtime, artifactValidator, reviewer, aggregator, SupervisorBudget.defaults()
        );

        GraphRunRequest req = new GraphRunRequest("r1", 1L, UUID.randomUUID(), "sess", "问题", false, null, null, RunMode.SYNC);
        GraphRunResult finalResult = supervisor.run(req, graph).get();

        assertThat(finalResult.runId()).isEqualTo("r1");
        assertThat(finalResult.artifacts()).containsKey("rep-1");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```powershell
mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=SupervisorWorkflowTest "-Dcopilot.neo4j.enabled=false"
```
Expected: FAIL with compilation error (SupervisorWorkflow not found).

- [ ] **Step 3: Write minimal implementation**

Modify `copilot-common/src/main/java/com/financial/copilot/common/event/ResearchStreamEvent.java` to add:
- `reviewStarted(String runId, int cycle, int maxCycles)`
- `reviewDecision(String runId, int cycle, String action, List<String> targetTaskIds, List<String> issues, double confidence)`
- `runDegraded(String runId, String reason, List<String> issues)`
- `replanTriggered(String runId, int cycle, String reason)`

Create `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/supervisor/SupervisorWorkflow.java`:
Implement `SupervisorWorkflow` tying together `PlanValidator`, `DagRuntime`, `DeterministicArtifactValidator`, `ReviewerAgent`, `ReportSynthesizerAgent`, and `SupervisorStateMachine`.

- [ ] **Step 4: Run test to verify it passes**

Run:
```powershell
mvn -s maven-settings.xml -pl copilot-agent-core test -Dtest=SupervisorWorkflowTest "-Dcopilot.neo4j.enabled=false"
```
Expected: PASS.

- [ ] **Step 5: Preserve changes**
Verify git status is clean.

---

### Task 7: PostgreSQL Node Lease Claiming and Event Single-Write Persistence

**Files:**
- Modify: `copilot-domain/src/main/java/com/financial/copilot/domain/platform/execution/port/ResearchExecutionPort.java`
- Modify: `copilot-data-engine/src/main/java/com/financial/copilot/data/execution/adapter/PostgresResearchExecutionAdapter.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/runtime/DagRuntime.java`
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/workflow/FinancialResearchWorkflow.java`
- Test: `copilot-data-engine/src/test/java/com/financial/copilot/data/execution/adapter/PostgresResearchExecutionAdapterTest.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/dag/runtime/DagRuntimeTest.java`

**Interfaces:**
- Consumes: `ResearchExecutionPort`, `NodeAttemptMapper`, `DagRuntime`
- Produces: `ResearchExecutionPort.claimAttempt(UUID runId, String nodeId, int attempt, String workerId, Instant leaseUntil)`

- [ ] **Step 1: Write the failing unit tests for claimAttempt in PostgresResearchExecutionAdapterTest**

Update `copilot-data-engine/src/test/java/com/financial/copilot/data/execution/adapter/PostgresResearchExecutionAdapterTest.java` to assert `claimAttempt` behavior:

```java
@Test
@DisplayName("单节点尝试抢占成功：claimAttempt 返回 true 并设置 RUNNING 状态与租约")
void testClaimSingleAttemptSuccess() {
    UUID runId = UUID.randomUUID();
    String nodeId = "node-1";
    int attempt = 1;
    String workerId = "worker-A";
    Instant leaseUntil = Instant.now().plus(Duration.ofMinutes(5));

    when(nodeAttemptMapper.claimAttempt(eq(runId), eq(nodeId), eq(attempt), eq(workerId), any()))
            .thenReturn(1);

    boolean success = adapter.claimAttempt(runId, nodeId, attempt, workerId, leaseUntil);
    assertThat(success).isTrue();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```powershell
mvn -s maven-settings.xml -pl copilot-data-engine test -Dtest=PostgresResearchExecutionAdapterTest "-Dcopilot.neo4j.enabled=false"
```
Expected: FAIL with method not found `claimAttempt`.

- [ ] **Step 3: Write minimal implementation**

In `copilot-domain/src/main/java/com/financial/copilot/domain/platform/execution/port/ResearchExecutionPort.java`:
Add:
```java
/**
 * 抢占指定的单节点执行尝试（设置状态为 RUNNING 并绑定 workerId 与租约截止期）
 *
 * @param runId 运行 ID
 * @param nodeId 节点 ID
 * @param attempt 尝试序号
 * @param workerId 工作节点标识
 * @param leaseUntil 租约有效期
 * @return 是否抢占成功
 */
boolean claimAttempt(UUID runId, String nodeId, int attempt, String workerId, Instant leaseUntil);
```

In `copilot-data-engine/src/main/java/com/financial/copilot/data/execution/adapter/PostgresResearchExecutionAdapter.java`:
Implement `claimAttempt`:
```java
@Override
@Transactional("jdbcTransactionManager")
public boolean claimAttempt(UUID runId, String nodeId, int attempt, String workerId, Instant leaseUntil) {
    Objects.requireNonNull(runId, "runId cannot be null");
    Objects.requireNonNull(nodeId, "nodeId cannot be null");
    Objects.requireNonNull(workerId, "workerId cannot be null");
    Objects.requireNonNull(leaseUntil, "leaseUntil cannot be null");
    int rows = nodeAttemptMapper.claimAttempt(runId, nodeId, attempt, workerId, toLocalDateTime(leaseUntil));
    return rows > 0;
}
```

In `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/dag/runtime/DagRuntime.java`:
In `submitToVirtualThread(...)`:
Before `executeWithTimeout`, call `executionPort.claimAttempt(runUuid, nodeId, node.getRetryCount() + 1, workerId, Instant.now().plus(Duration.ofMinutes(5)))`.
In `FinancialResearchWorkflow.java`:
Ensure that `eventBus` does not duplicate writes when `DagRuntime` is already persisting stream events.

Install updated upstream modules:
```powershell
mvn -s maven-settings.xml -pl copilot-domain,copilot-data-engine install -DskipTests
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```powershell
mvn -s maven-settings.xml -pl copilot-data-engine test -Dtest=PostgresResearchExecutionAdapterTest "-Dcopilot.neo4j.enabled=false"
```
Expected: PASS.

- [ ] **Step 5: Preserve changes**
Verify git status is clean.

---

### Task 8: Wire FinancialResearchWorkflow, WebFlux Controllers, and Regression Testing

**Files:**
- Modify: `copilot-agent-core/src/main/java/com/financial/copilot/agent/core/infra/workflow/FinancialResearchWorkflow.java`
- Modify: `copilot-app/src/main/java/com/financial/copilot/controller/ResearchRunController.java`
- Modify: `copilot-app/src/main/java/com/financial/copilot/controller/ResearchRunLifecycle.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/infra/workflow/FinancialResearchWorkflowTest.java`
- Test: `copilot-app/src/test/java/com/financial/copilot/controller/ResearchRunControllerTest.java`
- Test: `copilot-agent-core/src/test/java/com/financial/copilot/agent/core/architecture/NoLegacyExecutionArchitectureTest.java`

**Interfaces:**
- Consumes: `SupervisorWorkflow`, `FinancialResearchWorkflow`, `ResearchRunLifecycle`
- Produces: Full end-to-end integration and API compatibility

- [ ] **Step 1: Write integration and controller test cases for degraded runs**

Update `copilot-app/src/test/java/com/financial/copilot/controller/ResearchRunControllerTest.java` and `FinancialResearchWorkflowTest.java` to test runs that complete with `DEGRADED` status, asserting that `report()` extracts the partial report and billing settlement settles actual points.

- [ ] **Step 2: Run test to verify current state**

Run:
```powershell
mvn -s maven-settings.xml -pl copilot-app test -Dtest=ResearchRunControllerTest "-Dcopilot.neo4j.enabled=false"
```

- [ ] **Step 3: Wire SupervisorWorkflow into FinancialResearchWorkflow and Controllers**

Update `FinancialResearchWorkflow.java`:
Inject `SupervisorWorkflow` and delegate execution planning/reviewing/aggregating loop through it.
Update `ResearchRunLifecycle.java`:
Support `DEGRADED` status alongside `SUCCEEDED` for report generation and settlement.

- [ ] **Step 4: Run multi-module regression test suite**

Run:
```powershell
mvn -s maven-settings.xml -pl copilot-agent-core,copilot-data-engine,copilot-app test "-Dcopilot.neo4j.enabled=false"
```
Expected: All tests pass across modules without needing live Neo4j, Redis, or Ollama.

- [ ] **Step 5: Final working-tree review**
Review `git status` to ensure only the intended files were modified.
