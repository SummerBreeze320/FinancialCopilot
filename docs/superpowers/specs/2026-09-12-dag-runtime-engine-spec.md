# 动态多 Agent 图运行时引擎规范 (Dynamic Multi-Agent DAG Runtime Engine Specification)

> **版本**：v1.0.0  
> **日期**：2026-09-12  
> **状态**：Draft (Design Finalized)  
> **目标运行时**：Java 21 LTS 纯原生（无 Java 17 兼容包袱，全面启用 Virtual Threads、Record Patterns 与模式匹配）

---

## 1. 架构目标与背景

当前系统的投研工作流 `FinancialResearchWorkflow` 基于简单的线性循环执行 `for (SubTask step : plan.getSteps())`，且依赖单例字符串 Key 的 `ResearchBlackboard`。存在以下核心瓶颈：
1. **静态串行限制**：无数据依赖的独立任务无法并发执行，耗时线性累加；
2. **缺乏动态自适应**：生成后的计划是不可变静态链条，无法根据中间节点输出动态修正后续步骤（无法应对初筛为 0 或突发黑天鹅下钻等场景）；
3. **黑板弱类型冲突**：字符串 Key 缺乏类型契约与证据链，多分支并发写入易踩踏；
4. **规划器能力受限**：Planner 依赖大模型裸奔猜想，缺乏金融指标库与系统能力检索工具支撑。

### 核心设计原则
* **Dependency-Driven 运行时**：Wavefront 仅用于静态拓扑分层与 UI 列布局，**运行时绝不设物理屏障（No Runtime Barrier）**，任何节点依赖就绪立即派发执行；
* **全局 Graph + 局部 ReAct + 动态图演进**：节点产物反哺给 Planner，支持运行时对 Graph 进行加节点、剪枝、调参等动态演进；
* **工具增强型 Planner (Tool-Augmented ReAct)**：装配金融指标智库（Metric RAG）、技能注册表（Skill Search）与系统算力清单，确保拆解严密；
* **多模态强类型产物 (Multi-Modal Artifact)**：中间状态统一为包含文本、结构化数据、前端交互组件、信源引用与审计证据的 `Artifact<T>`；
* **纯粹原生 Java 21**：直接采用 `Executors.newVirtualThreadPerTaskExecutor()`，废除任何 Java 17 兼容反射与回退逻辑。

---

## 2. 系统全景架构与四层职责隔离 (Four-Tier Architecture)

系统遵循**“任务层规划目标与契约，执行层调度数据流，Agent 局部自适应推理，Tool 提供原子能力”**的四层干净解耦体系：

```text
┌────────────────────────────────────────────────────────────────────────┐
│ 1. Graph Planner  ──► 任务层规划 (WHAT / WHY / DEPENDENCY)            │
│    - 目标: 识别业务意图，拆解高维任务节点与产物契约                   │
│    - 规则: 仅定义“做什么”和“产出什么”，绝不微操 Agent 内部调用细节      │
└────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 2. DAG Runtime    ──► 执行层调度 (WHEN / CONCURRENCY / FLOW)           │
│    - 机制: 依赖就绪即派发，Java 21 虚拟线程并发执行，无物理阻塞屏障    │
│    - 数据: 自动从 ArtifactStore 提取上游强类型产物注入下游            │
│    - 状态: 维护 PENDING -> READY -> RUNNING -> SUCCEEDED 状态机       │
└────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 3. Node Agent     ──► 局部推理层 (HOW / LOCAL ReAct)                   │
│    - 具身: Agent 内部运行自主 ReAct 闭环 (Reason -> Tool -> Reflect)  │
│    - 弹性: 自主处理数据缺失、指标计算与异常自愈，交付最终 Artifact    │
└────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 4. Atomic Tools   ──► 原子能力层 (EXECUTION / I/O)                     │
│    - 纯粹物理能力: 净值抓取、数学公式计算、Wind/东财 API、向量检索     │
└────────────────────────────────────────────────────────────────────────┘
```

### 2.1 运行时全景协同蓝图

```text
                             ┌────────────────────────┐
                             │  ReAct Graph Planner   │◄────────────────┐
                             │ (Tool-Augmented 规划器) │                 │
                             └───────────┬────────────┘                 │
                                         │                              │
                                     生成/增量演进                       │
                                         ▼                              │
                             ┌────────────────────────┐                 │
                             │     ExecutionGraph     │                 │
                             │ (双向邻接表，支持增删改查) │                 │
                             └───────────┬────────────┘                 │
                                         │ 提交调度                      │
                                         ▼                              │
                             ┌────────────────────────┐                 │
                             │       DagRuntime       │                 │
                             │   (依赖就绪即派发调度器)  │                 │
                             └───────────┬────────────┘                 │
                                         │ Java 21 虚拟线程池并发派发     │
                                         ▼                              │
                                   [Node A: 执行]                       │
                                         │ 产出 Artifact                 │
                                         ▼                              │
                             ┌────────────────────────┐                 │
                             │      Node Result       │                 │
                             └─────┬────────────┬─────┘                 │
                                   │            │                       │
                                   ▼            ▼                       │
                            [ArtifactStore]  [Re-plan Checkpoint]───────┘
                                                  │
                                                  ├─► 1. 保持 (KEEP)：下游继续放行
                                                  ├─► 2. 插桩 (ADD)：发现新事实，动态追加节点
                                                  ├─► 3. 剪枝 (PRUNE)：初筛为空，级联跳过
                                                  └─► 4. 调参 (MODIFY)：动态重设后续输入
```

---

## 3. 核心抽象与数据模型规范

### 3.1 节点生命周期状态机 (NodeStatus)

```java
package com.financial.copilot.agent.core.dag.model;

/**
 * <h1>DAG 节点生命周期状态</h1>
 */
public enum NodeStatus {
    /** 初始等待态：前驱依赖尚未全部就绪 */
    PENDING,
    /** 就绪态：前驱依赖全部满足，已进入调度队列 */
    READY,
    /** 运行态：Agent 正在虚拟线程中执行 */
    RUNNING,
    /** 成功态：执行完成，产物已归档至 ArtifactStore */
    SUCCEEDED,
    /** 失败态：发生异常且不可恢复 */
    FAILED,
    /** 级联跳过：前驱失败或由 Planner 动态剪枝 */
    SKIPPED,
    /** 取消态：外部主动中止会话 */
    CANCELLED,
    /** 超时态：单节点执行超时 */
    TIMEOUT
}
```

### 3.2 双向邻接图模型 (ExecutionGraph & GraphNode)

```java
package com.financial.copilot.agent.core.dag.model;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>DAG 拓扑执行图</h1>
 * 维护双向邻接表，支持静态拓扑分析与运行时动态变轨（加减节点/边）。
 */
public class ExecutionGraph {
    private final String graphId;
    private final Map<String, GraphNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> upstream = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> downstream = new ConcurrentHashMap<>();

    public ExecutionGraph(String graphId) {
        this.graphId = graphId;
    }

    public synchronized void addNode(GraphNode node) {
        nodes.put(node.getNodeId(), node);
        upstream.putIfAbsent(node.getNodeId(), ConcurrentHashMap.newKeySet());
        downstream.putIfAbsent(node.getNodeId(), ConcurrentHashMap.newKeySet());
    }

    public synchronized void addEdge(String fromNodeId, String toNodeId) {
        if (!nodes.containsKey(fromNodeId) || !nodes.containsKey(toNodeId)) {
            throw new IllegalArgumentException("Nodes must exist in graph before adding edge");
        }
        downstream.get(fromNodeId).add(toNodeId);
        upstream.get(toNodeId).add(fromNodeId);
        if (hasCycle()) {
            // 回滚并报错
            downstream.get(fromNodeId).remove(toNodeId);
            upstream.get(toNodeId).remove(fromNodeId);
            throw new IllegalStateException("Adding edge " + fromNodeId + " -> " + toNodeId + " creates a cycle!");
        }
    }

    public synchronized void removeNode(String nodeId) {
        nodes.remove(nodeId);
        Set<String> parents = upstream.remove(nodeId);
        if (parents != null) {
            parents.forEach(p -> {
                Set<String> children = downstream.get(p);
                if (children != null) children.remove(nodeId);
            });
        }
        Set<String> children = downstream.remove(nodeId);
        if (children != null) {
            children.forEach(c -> {
                Set<String> pars = upstream.get(c);
                if (pars != null) pars.remove(nodeId);
            });
        }
    }

    public boolean hasCycle() {
        // 基于 Kahn 算法入度消除检测
        Map<String, Integer> inDegree = new HashMap<>();
        nodes.keySet().forEach(id -> inDegree.put(id, upstream.getOrDefault(id, Set.of()).size()));
        Queue<String> queue = new ArrayDeque<>();
        inDegree.forEach((id, deg) -> { if (deg == 0) queue.offer(id); });

        int visited = 0;
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            visited++;
            for (String next : downstream.getOrDefault(curr, Set.of())) {
                int newDeg = inDegree.compute(next, (k, d) -> d - 1);
                if (newDeg == 0) queue.offer(next);
            }
        }
        return visited != nodes.size();
    }

    /**
     * 计算节点的拓扑层级 (Wave Index)，仅作为 UI 布局和展示元数据，不作为运行屏障
     */
    public int calculateTopologicalWave(String nodeId) {
        Set<String> parents = upstream.getOrDefault(nodeId, Set.of());
        if (parents.isEmpty()) return 0;
        return parents.stream()
                .mapToInt(this::calculateTopologicalWave)
                .max()
                .orElse(-1) + 1;
    }

    public Map<String, GraphNode> getNodes() { return Collections.unmodifiableMap(nodes); }
    public Set<String> getUpstream(String nodeId) { return Collections.unmodifiableSet(upstream.getOrDefault(nodeId, Set.of())); }
    public Set<String> getDownstream(String nodeId) { return Collections.unmodifiableSet(downstream.getOrDefault(nodeId, Set.of())); }
    public String getGraphId() { return graphId; }
}
```

#### GraphNode 节点契约：
```java
package com.financial.copilot.agent.core.dag.model;

import java.time.Duration;
import java.util.*;

public class GraphNode {
    private final String nodeId;
    private final String taskType;             // SCREENING, BATCH_ANALYSIS, COMPARISON, SYNTHESIS, etc.
    private final String name;                 // 可读名称
    private final Set<ArtifactType> requiredInputs; // 声明所要求的输入产物类型
    private final ArtifactType outputType;     // 声明输出产物类型
    private final Map<String, Object> params;  // 结构化参数
    private final Duration timeout;            // 单步超时控制
    private final boolean failSoft;            // 是否容错（失败时不阻断其他分支）

    // Builder, Getters...
}
```

---

## 4. 多模态强类型投研产物标准 (Artifact Model)

彻底替代传统 `Map<String, Object>` 黑板，建立涵盖**观点、数据、组件、引用与证据**的规范：

```java
package com.financial.copilot.agent.core.dag.artifact;

import java.util.List;
import java.util.Map;

/**
 * <h1>多模态标准化投研产物</h1>
 */
public record Artifact<T>(
    String artifactId,
    String producerNodeId,
    ArtifactType type,
    String text,                                // 自然语言分析与核心观点
    T structuredData,                           // 强类型领域实体对象 (如 FundPool, MetricsMatrix)
    List<ArtifactComponent> components,         // 前端富交互组件渲染规格 (图表、卡片)
    List<ArtifactReference> references,         // 信源引用 (Wind、公告、财报等溯源)
    List<ArtifactEvidence> evidences,           // 支撑观点的量化事实锚点
    ArtifactMetadata metadata                   // 执行耗时、Token 开销、置信度等
) {
    public static <T> Artifact<T> of(String producerNodeId, ArtifactType type, T structuredData, String text) {
        return new Artifact<>(
            "art_" + UUID.randomUUID().toString().substring(0, 8),
            producerNodeId,
            type,
            text,
            structuredData,
            List.of(),
            List.of(),
            List.of(),
            ArtifactMetadata.now()
        );
    }
}
```

### 产物总线 (ArtifactStore)
```java
package com.financial.copilot.agent.core.dag.artifact;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ArtifactStore {
    private final Map<String, Artifact<?>> artifactsByNode = new ConcurrentHashMap<>();
    private final Map<String, Object> globalContext = new ConcurrentHashMap<>();

    public void store(String nodeId, Artifact<?> artifact) {
        artifactsByNode.put(nodeId, artifact);
    }

    @SuppressWarnings("unchecked")
    public <T> Artifact<T> get(String nodeId) {
        return (Artifact<T>) artifactsByNode.get(nodeId);
    }

    public <T> Optional<Artifact<T>> findFirstByType(ArtifactType type) {
        return artifactsByNode.values().stream()
                .filter(a -> a.type() == type)
                .map(a -> (Artifact<T>) a)
                .findFirst();
    }

    public Map<String, Artifact<?>> getAllUpstream(Set<String> upstreamNodeIds) {
        Map<String, Artifact<?>> result = new HashMap<>();
        upstreamNodeIds.forEach(id -> {
            Artifact<?> art = artifactsByNode.get(id);
            if (art != null) result.put(id, art);
        });
        return result;
    }
}
```

---

## 5. 依赖驱动运行时调度器 (DagRuntime)

### 5.1 纯原生 Java 21 虚拟线程执行器
* **无物理屏障**：每个节点直接通过 `CompletableFuture` 或依赖计数器监听直接父节点完成事件；
* **原生虚拟线程**：统一采用 `Executors.newVirtualThreadPerTaskExecutor()`，消灭 OS 线程池切换开销；
* **原子状态跃迁**：基于 `ConcurrentHashMap` 与 `AtomicReference` 维护 `NodeStatus`。

```java
package com.financial.copilot.agent.core.dag.runtime;

import java.util.concurrent.*;

public class DagRuntime {

    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final NodeExecutor nodeExecutor;
    private final ArtifactStore artifactStore;
    private final RePlanAdvisor rePlanAdvisor; // 动态 Re-plan 顾问

    public CompletableFuture<Void> executeGraph(
            ExecutionGraph graph,
            Consumer<DagEvent> eventPublisher
    ) {
        Map<String, CompletableFuture<Void>> futures = new ConcurrentHashMap<>();
        Map<String, NodeStatus> statusMap = new ConcurrentHashMap<>();
        
        // 标记所有节点初始为 PENDING
        graph.getNodes().keySet().forEach(id -> statusMap.put(id, NodeStatus.PENDING));

        // 提交初始入度为 0 的节点 (READY)
        for (GraphNode node : graph.getNodes().values()) {
            wireNodeExecution(node.getNodeId(), graph, futures, statusMap, eventPublisher);
        }

        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]));
    }

    private void wireNodeExecution(
            String nodeId,
            ExecutionGraph graph,
            Map<String, CompletableFuture<Void>> futures,
            Map<String, NodeStatus> statusMap,
            Consumer<DagEvent> eventPublisher
    ) {
        futures.computeIfAbsent(nodeId, id -> {
            Set<String> parentIds = graph.getUpstream(id);
            
            CompletableFuture<Void> parentsReady;
            if (parentIds.isEmpty()) {
                parentsReady = CompletableFuture.completedFuture(null);
            } else {
                CompletableFuture<?>[] parentFutures = parentIds.stream()
                        .map(pId -> wireNodeExecution(pId, graph, futures, statusMap, eventPublisher))
                        .toArray(CompletableFuture[]::new);
                parentsReady = CompletableFuture.allOf(parentFutures);
            }

            // 核心：直接父节点一就绪，立即在新虚拟线程激活当前节点！
            return parentsReady.thenRunAsync(() -> {
                GraphNode node = graph.getNodes().get(id);
                if (node == null) return;

                statusMap.put(id, NodeStatus.RUNNING);
                eventPublisher.accept(new DagEvent(id, NodeStatus.RUNNING, "Node started"));

                try {
                    // 执行业务逻辑
                    Artifact<?> result = nodeExecutor.execute(node, artifactStore);
                    artifactStore.store(id, result);
                    statusMap.put(id, NodeStatus.SUCCEEDED);
                    eventPublisher.accept(new DagEvent(id, NodeStatus.SUCCEEDED, "Node succeeded", result));

                    // 触发动态 Re-plan 评估 (局部反馈闭环)
                    if (rePlanAdvisor != null) {
                        rePlanAdvisor.onNodeCompleted(graph, node, result, this);
                    }
                } catch (Exception e) {
                    if (node.isFailSoft()) {
                        statusMap.put(id, NodeStatus.FAILED);
                        eventPublisher.accept(new DagEvent(id, NodeStatus.FAILED, "Node failed softly: " + e.getMessage()));
                    } else {
                        statusMap.put(id, NodeStatus.FAILED);
                        throw new CompletionException(e);
                    }
                }
            }, virtualThreadExecutor);
        });
    }
}
```

### 5.2 资源感知并发限流器 (Resource-Aware ConcurrencyLimiter)

> **核心原则**：**Virtual Thread ≠ 无限并发！**  
> 投研工作流绝大多数任务为 I/O-bound（LLM HTTP、AkShare/Wind 数据源、向量数据库、PostgreSQL、远程组件）。虽然 Java 21 虚拟线程极其轻量（可创建数十万个），但下游物理资源均有硬性容量瓶颈：
> 1. 大模型 API：具备 RPM / TPM 限额，瞬时超额将触发 HTTP 429；
> 2. 数据库与数据源：HikariCP 连接池容量有限，外部数据端口有防爬并发限制。

因此，**Graph 允许 100 个 Ready Node，但绝不能无脑无界并行打爆下游**。调度器内嵌基于 `Semaphore` 的 `ConcurrencyLimiter`：
* 当虚拟线程获取不到许可证（Permit）在 `Semaphore.acquire()` 阻塞时，JVM 自动将该虚拟线程从 Carrier 平台线程上卸载（Unmount），**完全不浪费 OS 线程资源**；
* 许可证一旦释放，JVM 自动唤醒并在可用平台线程上恢复调度。

```java
package com.financial.copilot.agent.core.dag.runtime;

import java.util.concurrent.Semaphore;
import java.util.concurrent.Callable;

/**
 * <h1>DAG 运行时资源感知限流器</h1>
 */
public class ConcurrencyLimiter {

    /** 业务 Agent 最大并发数 (默认 8) */
    private final Semaphore agentSemaphore;

    /** 大模型推理 API 最大并发数 (默认 4) */
    private final Semaphore llmSemaphore;

    /** 金融数据端口/数据库拉取最大并发数 (默认 10) */
    private final Semaphore dataPortSemaphore;

    public ConcurrencyLimiter(int maxAgent, int maxLlm, int maxDataPort) {
        this.agentSemaphore = new Semaphore(maxAgent);
        this.llmSemaphore = new Semaphore(maxLlm);
        this.dataPortSemaphore = new Semaphore(maxDataPort);
    }

    public <T> T runWithAgentPermit(Callable<T> task) throws Exception {
        agentSemaphore.acquire();
        try {
            return task.call();
        } finally {
            agentSemaphore.release();
        }
    }

    public <T> T runWithLlmPermit(Callable<T> task) throws Exception {
        llmSemaphore.acquire();
        try {
            return task.call();
        } finally {
            llmSemaphore.release();
        }
    }

    public <T> T runWithDataPortPermit(Callable<T> task) throws Exception {
        dataPortSemaphore.acquire();
        try {
            return task.call();
        } finally {
            dataPortSemaphore.release();
        }
    }
}
```

---

## 6. 工具增强型规划器 (Tool-Augmented ReAct GraphPlanner)

### 6.1 Planner 工具集定义
Planner 不再直接单次 Prompt 生成计划，而是通过 ReAct 循环使用如下工具：
1. **`MetricRAGTool`**：输入语义概念（如“三年稳健”、“对冲风险”、“抗跌能力”），检索并输出严密的量化指标集合（`annual_return`, `max_drawdown`, `sharpe_ratio`, `calmar_ratio`）；
2. **`SkillRegistryTool`**：查询当前 Agent 具备的标准化能力清单（如 `.agents/skills` 中的 `asset-allocation`, `fund-screener` 等）；
3. **`CapabilityRegistryTool`**：获取 Spring 容器中当前注册的真实服务端口（如 `FundDataPort`、`EastMoneyPort`）；
4. **`MarketMemoryTool`**：检索 `LongTermMemoryService` 与近期宏观市场综述。

### 6.2 闭环动态自适应 (Dynamic Re-planning 决策)
当任意节点完成时，`RePlanAdvisor` 进行结构化评估：
* **`KEEP`**：产物符合预期，下游无需调整；
* **`ADD_NODE`**：如 Step 2 体检发现持仓踩雷，动态调用 `graph.addNode(forensicNode)` 与 `graph.addEdge(...)` 追加下钻分析；
* **`MODIFY_NODE`**：如初筛仅剩 1 只标的，将下游原本的双标的 `COMPARISON` 动态降级修改为单标的 `DEEP_DIVE`；
* **`PRUNE_BRANCH`**：前驱失败或数据缺失，将其直接下游标记为 `SKIPPED`，直接流向合成汇总节点。

---

## 7. 实施计划与里程碑

```text
┌─────────────────────────────────────────────────────────────────────────┐
│ Milestone 1: 原生图内核与标准产物层 (Core Engine & Artifact)              │
│ - 实现 ExecutionGraph, GraphNode, NodeStatus, Artifact<T>, ArtifactStore│
│ - 实现 DagRuntime (Java 21 Virtual Threads 依赖驱动，无屏障调度)           │
│ - 全套单元测试覆盖（菱形依赖、成环检测、并发就绪、Fail-Soft 容错）          │
├─────────────────────────────────────────────────────────────────────────┤
│ Milestone 2: 工作流集成与黑板适配器 (Workflow Integration)                │
│ - 实现 LegacyPlanAdapter：现存 ExecutionPlan 平滑转换为 ExecutionGraph    │
│ - 实现 BlackboardAdapter：现有 Agent 无需重构，透明映射到 ArtifactStore    │
│ - 替换 FinancialResearchWorkflow 内的线性循环，升级为并发 DagRuntime     │
├─────────────────────────────────────────────────────────────────────────┤
│ Milestone 3: SSE 流式并发协议与多卡片渲染 (Reactive SSE Protocol)         │
│ - 扩展 ResearchStreamEvent 携带 stepId, waveIndex, parentIds, status    │
│ - 验证前端并发分支树的实时流式推进                                       │
├─────────────────────────────────────────────────────────────────────────┤
│ Milestone 4: Tool-Augmented Planner & 动态 Re-plan 闭环                  │
│ - 装配 Metric RAG 与 SkillRegistry 给 TaskDecomposer                    │
│ - 实现 RePlanAdvisor，打通中间产物驱动图动态演进的完整自治闭环            │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 8. 验证与验收标准

1. **并发性验证**：当执行多赛道/多标的初筛时（如“半导体 + 新能源”），两个任务的启动时间戳完全重合，总耗时从 $T_1 + T_2$ 骤降至 $\max(T_1, T_2)$；
2. **木桶效应消除验证**：针对长短任务依赖（A=1s -> D, B=5s -> E），D 节点必须在第 1s 准时启动，绝不等待 B 节点；
3. **零回归**：全工程 8 个 Maven 模块现存所有单测保持 100% 通过（`BUILD SUCCESS`）；
4. **纯粹 Java 21**：无任何反射或 Java 17 回退，全链路运行在 Oracle JDK 21.0.12 之上。
