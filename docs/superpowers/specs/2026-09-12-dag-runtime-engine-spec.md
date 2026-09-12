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

### 2.1 运行时全景收敛架构 (Converged Final Architecture)

```text
                         User Query
                             │
                             ▼
                    ┌────────────────┐
                    │  Graph Planner │
                    │     ReAct      │
                    └───────┬────────┘
                            │
               RAG / Skill / Capability
                            │
                            ▼
                    Execution Graph
                            │
                            ▼
                 ┌─────────────────────┐
                 │      DAG Runtime    │
                 │                     │
                 │ Dependency Resolver │
                 │ Ready Queue         │
                 │ ConcurrencyLimiter  │
                 │ Retry/Timeout       │
                 └─────────┬───────────┘
                           │
             ┌─────────────┼─────────────┐
             ▼             ▼             ▼
          Agent A       Agent B       Agent C
           ReAct         ReAct         ReAct
             │             │             │
             └───────┬─────┴─────┬───────┘
                     ▼           ▼
                   Artifact Store
                          │
                          ▼
                   Node Completed
                          │
              ┌───────────┴───────────┐
              ▼                       ▼
        Dependency Resolver       Graph Planner
              │                       │
          Ready Node             是否需要改图？
              │                       │
              └───────────────┬───────┘
                              ▼
                       Updated Graph
                              │
                              ▼
                         DAG Runtime
                              │
                              ▼
                      Final Synthesizer
                              │
                              ▼
                            Report
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

    /**
     * 原子应用增量差分补丁 (GraphPatch)
     */
    public synchronized int applyPatch(GraphPatch patch) {
        if (this.revision != patch.baseRevision()) {
            throw new ConcurrentModificationException("Graph revision mismatch! Current: " + revision + ", Patch base: " + patch.baseRevision());
        }
        for (GraphOperation op : patch.operations()) {
            switch (op.op()) {
                case ADD_NODE -> addNode(op.node());
                case REMOVE_NODE -> removeNode(op.nodeId());
                case ADD_EDGE -> addEdge(op.from(), op.to());
                case REMOVE_EDGE -> removeEdge(op.from(), op.to());
                case UPDATE_NODE -> updateNodeParams(op.nodeId(), op.params());
                case SKIP_NODE -> markNodeSkipped(op.nodeId());
                case RETRY_NODE -> markNodeForRetry(op.nodeId());
            }
        }
        if (hasCycle()) {
            throw new IllegalStateException("Applying patch created a circular dependency cycle!");
        }
        return ++this.revision;
    }

    public int getRevision() { return revision; }
    public Map<String, GraphNode> getNodes() { return Collections.unmodifiableMap(nodes); }
    public Set<String> getUpstream(String nodeId) { return Collections.unmodifiableSet(upstream.getOrDefault(nodeId, Set.of())); }
    public Set<String> getDownstream(String nodeId) { return Collections.unmodifiableSet(downstream.getOrDefault(nodeId, Set.of())); }
    public String getGraphId() { return graphId; }
}

/**
 * <h1>DAG 增量差分补丁模型 (Graph Patch)</h1>
 * Planner 在节点完成后只输出精准的增量操作，禁止整图覆写！
 */
public record GraphPatch(
    int baseRevision,
    List<GraphOperation> operations
) {
    public static GraphPatch of(int baseRevision, GraphOperation... ops) {
        return new GraphPatch(baseRevision, List.of(ops));
    }
}

public record GraphOperation(
    PatchOp op,
    GraphNode node,
    String nodeId,
    String from,
    String to,
    Map<String, Object> params
) {
    public static GraphOperation addNode(GraphNode node) {
        return new GraphOperation(PatchOp.ADD_NODE, node, node.getNodeId(), null, null, null);
    }
    public static GraphOperation addEdge(String from, String to) {
        return new GraphOperation(PatchOp.ADD_EDGE, null, null, from, to, null);
    }
    public static GraphOperation skipNode(String nodeId) {
        return new GraphOperation(PatchOp.SKIP_NODE, null, nodeId, null, null, null);
    }
    public static GraphOperation updateNode(String nodeId, Map<String, Object> params) {
        return new GraphOperation(PatchOp.UPDATE_NODE, null, nodeId, null, null, params);
    }
}

public enum PatchOp {
    ADD_NODE,
    REMOVE_NODE,
    ADD_EDGE,
    REMOVE_EDGE,
    UPDATE_NODE,
    SKIP_NODE,
    RETRY_NODE
}
```

#### GraphNode 节点契约与失败策略 (FailurePolicy)：

```java
package com.financial.copilot.agent.core.dag.model;

import java.time.Duration;
import java.util.*;

/**
 * <h1>节点级失败策略枚举</h1>
 */
public enum FailurePolicy {
    /** 强硬失败：当前节点失败则整个图或下游关键依赖链立即终止报错 (如核心标的初筛) */
    FAIL_FAST,
    /** 忽略失败继续流转：下游放行，产物标记 evidenceIncomplete=true，报告中注明 (如宏观新闻舆情) */
    CONTINUE,
    /** 指数退避重试：在当前节点内部重试 N 次 (默认配合 maxRetries=2) */
    RETRY,
    /** 降级保底数据：失败时注入预置 Fallback 数据，下游无缝继续 (如历史行情走备用源) */
    FALLBACK,
    /** 可选分支：若失败则整条支路静默标记为 SKIPPED，不阻断汇聚节点 (如可选估值模型) */
    OPTIONAL
}

public class GraphNode {
    private final String nodeId;
    private final String taskType;             // SCREENING, BATCH_ANALYSIS, COMPARISON, SYNTHESIS, etc.
    private final String name;                 // 可读名称
    private final Set<ArtifactType> requiredInputs; // 声明所要求的输入产物类型
    private final ArtifactType outputType;     // 声明输出产物类型
    private final Map<String, Object> params;  // 结构化参数
    private final Duration timeout;            // 单步超时控制
    private final FailurePolicy failurePolicy; // 节点级失败容错策略 (默认 CONTINUE)
    private final int maxRetries;              // 最大重试次数 (配合 RETRY 策略，默认 2)
    private final FallbackProvider fallbackProvider; // 降级数据提供者 (配合 FALLBACK 策略)
    private final ResourceRequirement resourceRequirement; // 物理资源配额需求 (如 LLM:1, DPU:1)
    private final NodePriority priority;       // 调度优先级 (HIGH, NORMAL, LOW)

    // Builder, Getters...
}
```

---

## 4. 多模态强类型投研产物标准 (Typed Artifact Contract)

彻底替代传统弱类型 `Map<String, Object>` 黑板，统一管理 `workspace`、`component`、`reference`、`component_data_read` 与 `tool result`：

```java
package com.financial.copilot.agent.core.dag.artifact;

import java.time.Instant;
import java.util.List;

/**
 * <h1>强类型投研产物契约 (Typed Artifact Contract)</h1>
 *
 * @param <T> 领域主载荷类型 (如 FundPool, FundResearchResult, MacroResearchResult 等)
 */
public record Artifact<T>(
    String id,                     // 产物全局唯一 ID (如 "art_fund_pool_001")
    String type,                   // 产物类型枚举标识 (FUND_POOL, MACRO_FACTS, etc.)
    String producerNodeId,         // 生产该产物的节点 ID (如 "fund_screen_1")
    T payload,                     // 强类型业务载荷实体
    ArtifactMetadata metadata      // 标准化元数据 (时间戳、信源、置信度、证据链)
) {
    public static <T> Artifact<T> of(String id, String type, String producerNodeId, T payload, ArtifactMetadata metadata) {
        return new Artifact<>(id, type, producerNodeId, payload, metadata);
    }
}

/**
 * <h1>产物审计与合规元数据 (Artifact Metadata)</h1>
 * 回答四个关键问题：结论来自什么数据？数据是否完整？置信度多少？由哪个 Agent/信源产生？
 */
public record ArtifactMetadata(
    Instant createdAt,             // 产生时间戳
    String schemaVersion,          // 契约结构版本号 (如 "1.0")
    List<String> evidenceIds,      // 支撑该结论的证据 ID 列表 (溯源到季报/公告/行情)
    Double confidence,             // 置信度打分 (0.0 ~ 1.0)
    boolean partial,               // 数据是否为部分降级结果 (true 表示数据不完整)
    String source                  // 物理信源渠道 (如 "Wind.API", "EastMoney.Crawler", "Internal.DB")
) {
    public static ArtifactMetadata standard(String source) {
        return new ArtifactMetadata(Instant.now(), "1.0", List.of(), 1.0, false, source);
    }

    public static ArtifactMetadata partial(String source, List<String> evidenceIds, String reason) {
        return new ArtifactMetadata(Instant.now(), "1.0", evidenceIds, 0.7, true, source);
    }
}
```

### 4.1 核心领域产物载荷 (Domain Artifact Payloads)

系统内置标准化投研业务载荷，坚决杜绝各 Agent 自行定义零散输出格式：
1. `Artifact<FundPool>`（初筛标的池）：包含命中基金清单、初筛命中理由与规模门槛；
2. `Artifact<MacroResearchResult>`（宏观流动性观点）：包含利率走势、货币政策定调、基准指数涨跌；
3. `Artifact<FundResearchResult>`（单基金/经理多维体检）：包含超额收益分解、回撤天数、评级得分矩阵；
4. `Artifact<ComparisonReport>`（决赛圈横向对标）：包含多标的对比雷达图、季报观点异同、综合打擂台胜出者；
5. `Artifact<DocumentEvidence>`（研报与公告证据点）：从招募说明书或财报中提纯的原文章节与事实锚点；
6. `Artifact<FinalSynthesisReport>`（终审投研研报）：Markdown 全文、核心资产配置权重与免责声明。
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

### 5.1 事件驱动图执行模型 (Event-Driven DAG Execution Engine)

> **重大架构决议**：**彻底弃用静态不可变的 `CompletableFuture.allOf(...)` 递归链条！**  
> 静态 `allOf(...)` 仅适合不可变的批处理图。在支持 Planner 动态 Re-plan（A 完成后动态插入 D、B 完成后动态剪枝 E）的高级 Agent 系统中，静态 Future 链条无法在运行时安全动态插桩与变轨。
> 
> **取而代之的是纯粹的 `NodeCompletionEvent` 事件驱动模式**：
> 1. 初始将所有入度为 0 的根节点标记为 `READY` 并派发；
> 2. 节点完成触发 `onNodeCompleted`；
> 3. 触发 Planner 的动态 Re-plan Checkpoint（支持即时 `graph.addNode / removeNode`）；
> 4. `DependencyResolver` 遍历当前完成节点的所有直接下游 `downstream`；
> 5. 若下游节点的所有 `upstream` 前驱已全部就绪（`SUCCEEDED` / `SKIPPED`），CAS 原子跃迁为 `READY` 并立即派发到虚拟线程执行；
> 6. 与系统 SSE、Agent Event 体系完全原生契合。

```java
package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.model.NodeStatus;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * <h1>基于事件驱动的 DAG 运行时引擎 (Event-Driven DAG Runtime)</h1>
 */
public class DagRuntime {

    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final NodeExecutor nodeExecutor;
    private final ArtifactStore artifactStore;
    private final ConcurrencyLimiter concurrencyLimiter;
    private final RePlanAdvisor rePlanAdvisor;

    public CompletableFuture<Void> executeGraph(
            ExecutionGraph graph,
            Consumer<DagEvent> eventPublisher
    ) {
        CompletableFuture<Void> graphCompletionFuture = new CompletableFuture<>();
        Map<String, AtomicReference<NodeStatus>> statusMap = new ConcurrentHashMap<>();
        AtomicInteger activeOrPendingNodes = new AtomicInteger(0);

        // 1. 初始化所有节点状态为 PENDING
        for (String nodeId : graph.getNodes().keySet()) {
            statusMap.put(nodeId, new AtomicReference<>(NodeStatus.PENDING));
            activeOrPendingNodes.incrementAndGet();
        }

        // 2. 启动入度为 0 的根节点
        Set<String> rootNodes = graph.getRootNodeIds();
        for (String rootId : rootNodes) {
            transitionAndDispatch(rootId, graph, statusMap, activeOrPendingNodes, graphCompletionFuture, eventPublisher);
        }

        return graphCompletionFuture;
    }

    /**
     * 原子状态跃迁并派发执行
     */
    private void transitionAndDispatch(
            String nodeId,
            ExecutionGraph graph,
            Map<String, AtomicReference<NodeStatus>> statusMap,
            AtomicInteger activeOrPendingNodes,
            CompletableFuture<Void> graphCompletionFuture,
            Consumer<DagEvent> eventPublisher
    ) {
        AtomicReference<NodeStatus> statusRef = statusMap.computeIfAbsent(
                nodeId, k -> new AtomicReference<>(NodeStatus.PENDING));

        // CAS 防止重复派发
        if (!statusRef.compareAndSet(NodeStatus.PENDING, NodeStatus.READY)) {
            return;
        }

        eventPublisher.accept(new DagEvent(nodeId, NodeStatus.READY, "Node is ready"));

        // 提交至虚拟线程池执行
        virtualThreadExecutor.submit(() -> {
            GraphNode node = graph.getNodes().get(nodeId);
            if (node == null) {
                onNodeCompleted(nodeId, NodeStatus.FAILED, null, null, graph, statusMap, activeOrPendingNodes, graphCompletionFuture, eventPublisher);
                return;
            }

            statusRef.set(NodeStatus.RUNNING);
            eventPublisher.accept(new DagEvent(nodeId, NodeStatus.RUNNING, "Node started"));

            try {
                // 结合 ConcurrencyLimiter 保护下游物理资源配额
                Artifact<?> result = concurrencyLimiter.runWithAgentPermit(
                        () -> nodeExecutor.execute(node, artifactStore)
                );
                artifactStore.store(nodeId, result);
                onNodeCompleted(nodeId, NodeStatus.SUCCEEDED, result, null, graph, statusMap, activeOrPendingNodes, graphCompletionFuture, eventPublisher);
            } catch (Exception e) {
                handleNodeFailure(node, e, graph, statusMap, activeOrPendingNodes, graphCompletionFuture, eventPublisher);
            }
        });
    }

    /**
     * 节点失败策略处理器 (基于 FailurePolicy 状态跃迁)
     */
    private void handleNodeFailure(
            GraphNode node,
            Exception e,
            ExecutionGraph graph,
            Map<String, AtomicReference<NodeStatus>> statusMap,
            AtomicInteger activeOrPendingNodes,
            CompletableFuture<Void> graphCompletionFuture,
            Consumer<DagEvent> eventPublisher
    ) {
        FailurePolicy policy = node.getFailurePolicy() != null ? node.getFailurePolicy() : FailurePolicy.CONTINUE;
        String nodeId = node.getNodeId();

        switch (policy) {
            case RETRY -> {
                // 结合重试计数器，重试未超限则重新提交，超限退化为 FAIL_FAST 或 CONTINUE
                log.warn("Node {} failed, scheduling retry: {}", nodeId, e.getMessage());
                transitionAndDispatch(nodeId, graph, statusMap, activeOrPendingNodes, graphCompletionFuture, eventPublisher);
            }
            case FALLBACK -> {
                Artifact<?> fallback = node.getFallbackProvider() != null 
                        ? node.getFallbackProvider().provideFallback(node, e)
                        : Artifact.incomplete(nodeId, node.getOutputType(), "Fallback: " + e.getMessage());
                artifactStore.store(nodeId, fallback);
                onNodeCompleted(nodeId, NodeStatus.SUCCEEDED, fallback, null, graph, statusMap, activeOrPendingNodes, graphCompletionFuture, eventPublisher);
            }
            case OPTIONAL -> {
                // 可选分支静默跳过，下游汇聚节点不等待该分支
                onNodeCompleted(nodeId, NodeStatus.SKIPPED, null, e, graph, statusMap, activeOrPendingNodes, graphCompletionFuture, eventPublisher);
            }
            case CONTINUE -> {
                // 标记 evidenceIncomplete=true 注入占位产物，允许下游继续合成并做出说明
                Artifact<?> degraded = Artifact.incomplete(nodeId, node.getOutputType(), e.getMessage());
                artifactStore.store(nodeId, degraded);
                onNodeCompleted(nodeId, NodeStatus.SUCCEEDED, degraded, null, graph, statusMap, activeOrPendingNodes, graphCompletionFuture, eventPublisher);
            }
            case FAIL_FAST -> {
                // 核心主链路失败，整图异常中断
                statusMap.get(nodeId).set(NodeStatus.FAILED);
                eventPublisher.accept(new DagEvent(nodeId, NodeStatus.FAILED, "Critical node failed fast: " + e.getMessage()));
                graphCompletionFuture.completeExceptionally(e);
            }
        }
    }

    /**
     * 核心：节点执行完成事件处理函数 (Node Completion Event Handler)
     */
    private void onNodeCompleted(
            String completedNodeId,
            NodeStatus finalStatus,
            Artifact<?> result,
            Throwable error,
            ExecutionGraph graph,
            Map<String, AtomicReference<NodeStatus>> statusMap,
            AtomicInteger activeOrPendingNodes,
            CompletableFuture<Void> graphCompletionFuture,
            Consumer<DagEvent> eventPublisher
    ) {
        statusMap.get(completedNodeId).set(finalStatus);
        eventPublisher.accept(new DagEvent(completedNodeId, finalStatus, 
                finalStatus == NodeStatus.SUCCEEDED ? "Node succeeded" : "Node failed: " + (error != null ? error.getMessage() : ""),
                result));

        // 1. 动态图反馈演进：通过 ReplanPolicy 条件门禁判定，绝不无脑每个节点都调 LLM！
        if (rePlanAdvisor != null && replanPolicy.shouldReplan(graph, completedNodeId, result, finalStatus)) {
            GraphPatch patch = rePlanAdvisor.planPatch(graph, completedNodeId, result);
            if (patch != null && !patch.operations().isEmpty()) {
                int newRev = graph.applyPatch(patch);
                // 注册 patch 中新增的节点到状态机
                for (GraphOperation op : patch.operations()) {
                    if (op.op() == PatchOp.ADD_NODE) {
                        statusMap.put(op.nodeId(), new AtomicReference<>(NodeStatus.PENDING));
                        activeOrPendingNodes.incrementAndGet();
                    }
                }
                eventPublisher.accept(new DagEvent(completedNodeId, NodeStatus.RUNNING, "Graph patched to rev " + newRev, patch));
            }
        }

        // 2. 依赖裁决：检查该节点的所有下游后继节点
        Set<String> downstreams = graph.getDownstream(completedNodeId);
        for (String childId : downstreams) {
            AtomicReference<NodeStatus> childStatus = statusMap.get(childId);
            if (childStatus != null && childStatus.get() == NodeStatus.PENDING) {
                Set<String> childUpstreams = graph.getUpstream(childId);
                
                boolean allUpstreamDone = childUpstreams.stream().allMatch(upId -> {
                    NodeStatus s = statusMap.get(upId) != null ? statusMap.get(upId).get() : null;
                    return s == NodeStatus.SUCCEEDED || s == NodeStatus.SKIPPED;
                });

                if (allUpstreamDone) {
                    // 所有依赖满足，下游节点立即就绪启动！
                    transitionAndDispatch(childId, graph, statusMap, activeOrPendingNodes, graphCompletionFuture, eventPublisher);
                }
            }
        }

        // 3. 图完成判定
        if (activeOrPendingNodes.decrementAndGet() == 0) {
            graphCompletionFuture.complete(null);
        }
    }
}
```

### 5.2 资源感知优先级调度器 (Resource-Aware Scheduling & ResourceManager)

> **核心原则**：**Virtual Thread ≠ 无限并发！**  
> 当 Planner 一次性生成 50 个扇出节点（例如 50 只标的并发评估）时，若同时打出 50 个 LLM 请求、50 个 DPU 调用和 20 个 RAG 向量检索，瞬间就会把下游接口打到 429 或将连接池耗尽。  
> 
> **每个节点显式声明资源需求 (`ResourceRequirement`) 与调度优先级 (`NodePriority`)**：
> 1. **物理资源细粒度配额**：
>    * `LLM`（大模型 API）：默认最大 4；
>    * `DPU`（数据引擎与数据库）：默认最大 10；
>    * `RAG`（向量与文档检索）：默认最大 20；
>    * `MCP`（外部协议与工具）：默认最大 10；
>    * `COMPONENT`（交互组件构建）：默认最大 8。
> 2. **三级优先级队列 (`PriorityReadyQueue`)**：
>    * `HIGH`：关键核心汇聚（如 Final Synthesis 终审研报合成、关键风控拦截）；
>    * `NORMAL`：普通投研业务节点（如标的筛选、体检分析）；
>    * `LOW`：后台弱时效任务（如长期记忆提纯、日志异步沉淀）。
> 3. **调度流转**：
>    * 节点依赖满足进入 `READY`；
>    * 尝试向 `ResourceManager` 申请配额：
>      * 成功：立即跃迁为 `RUNNING`，进入虚拟线程执行；
>      * 失败：推入按优先级排序的 `PriorityReadyQueue` 挂起；
>    * 任何节点执行结束释放配额时，自动触发 `drainReadyQueue()` 唤醒高优先级等待节点。

```java
package com.financial.copilot.agent.core.dag.runtime.resource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * <h1>声明式物理资源配额管理器 (Resource Manager)</h1>
 */
public class ResourceManager {

    private final Map<ResourceType, Semaphore> semaphores = new ConcurrentHashMap<>();

    public ResourceManager(Map<ResourceType, Integer> limits) {
        limits.forEach((type, maxPermits) -> semaphores.put(type, new Semaphore(maxPermits, true)));
    }

    public static ResourceManager defaultManager() {
        return new ResourceManager(Map.of(
            ResourceType.LLM, 4,
            ResourceType.DPU, 10,
            ResourceType.RAG, 20,
            ResourceType.MCP, 10,
            ResourceType.COMPONENT, 8
        ));
    }

    public boolean tryAcquire(ResourceRequirement requirement) {
        if (requirement == null) return true;
        Semaphore semaphore = semaphores.get(requirement.resourceType());
        return semaphore == null || semaphore.tryAcquire(requirement.permits());
    }

    public void acquire(ResourceRequirement requirement) throws InterruptedException {
        if (requirement == null) return;
        Semaphore semaphore = semaphores.get(requirement.resourceType());
        if (semaphore != null) {
            semaphore.acquire(requirement.permits());
        }
    }

    public void release(ResourceRequirement requirement) {
        if (requirement == null) return;
        Semaphore semaphore = semaphores.get(requirement.resourceType());
        if (semaphore != null) {
            semaphore.release(requirement.permits());
        }
    }
}

public record ResourceRequirement(
    ResourceType resourceType,
    int permits
) {
    public static ResourceRequirement of(ResourceType type, int permits) {
        return new ResourceRequirement(type, permits);
    }
    public static ResourceRequirement llm() { return new ResourceRequirement(ResourceType.LLM, 1); }
    public static ResourceRequirement dpu() { return new ResourceRequirement(ResourceType.DPU, 1); }
    public static ResourceRequirement rag() { return new ResourceRequirement(ResourceType.RAG, 1); }
}

public enum ResourceType {
    LLM,        // 大模型推理调用
    DPU,        // 数据计算与数据库拉取
    RAG,        // 知识向量库检索
    MCP,        // 外部工具接口
    COMPONENT   // 前端交互组件生成
}

public enum NodePriority {
    HIGH(10),   // 终审研报合成等关键路径
    NORMAL(5),  // 常规投研计算
    LOW(1);     // 后台记忆整理

    private final int weight;
    NodePriority(int weight) { this.weight = weight; }
    public int getWeight() { return weight; }
}
```

### 5.3 动态改图触发策略 (ReplanPolicy)

> **核心原则**：**绝不每个节点执行完都盲目唤醒 Planner 调 LLM！**  
> 95% 的投研节点（如标准净值序列抓取、公式计算）产出正常，每个节点盲目调大模型 Re-plan 会造成严重的成本浪费与延迟劣化。  
> 只有发生以下 **实质性异常或新事实** 时，才激活 Planner ReAct 生成 `GraphPatch`：
> 1. **节点失败**：核心或依赖节点执行异常，需自愈改道；
> 2. **数据不足**：标的初筛列表为空（count=0），或 `evidenceIncomplete=true`；
> 3. **发现新实体**：体检发现重仓股暴雷或基金经理突发变更；
> 4. **反思假定矛盾**：实际算出的指标与 Planner 初始假定背离；
> 5. **用户追加指令**：会话中用户插话调整研究侧重点。

```java
package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.NodeStatus;

import java.util.List;

/**
 * <h1>动态改图触发策略接口</h1>
 */
@FunctionalInterface
public interface ReplanPolicy {

    /**
     * 判断当前节点产物是否需要激活 Planner 生成 GraphPatch
     */
    boolean shouldReplan(
        ExecutionGraph graph,
        String completedNodeId,
        Artifact<?> result,
        NodeStatus status
    );

    /**
     * 默认规则型启发式策略 (零成本、纳秒级判定，不调 LLM)
     */
    static ReplanPolicy heuristic() {
        return (graph, nodeId, result, status) -> {
            // 1. 节点失败
            if (status == NodeStatus.FAILED) return true;
            if (result == null) return false;
            // 2. 产物明确标记为部分缺失/降级
            if (result.metadata() != null && result.metadata().partial()) return true;
            // 3. 初筛标的池为空 (无法进行后续分析)
            if (result.payload() instanceof List<?> list && list.isEmpty()) return true;
            return false;
        };
    }
}
```

### 5.4 结构化并发与树状级联取消 (Structured Concurrency & Hierarchical CancellationToken)

> **核心原则**：**父任务生命周期严格约束子任务，禁止任何孤儿线程与资源泄漏！**  
> 在生产环境中，用户随时可能关闭网页标签页、断开 SSE 连接或点击“停止生成”。  
> 如果没有结构化取消机制，后台将继续发生：
> * 多个虚拟线程仍在消耗高昂的大模型流式 Token；
> * DPU 仍在执行沉重的 SQL 与分时数据聚合；
> * 向量库持续承接无效并发检索。
>
> 结构化并发 (Structured Concurrency) 保证：**当一个逻辑单元（Run / Node）终止时，它所衍生出的所有并发子任务（Agent / Tool / Thread）必须在离开作用域前全部被收敛或取消。**

#### 5.4.1 四级树状取消作用域 (Four-Tier Hierarchical Scope)

系统引入树状结构 **`CancellationToken`**，贯穿 `Run -> Node -> Agent -> Tool` 四层调用栈：

```text
       HTTP / SSE Disconnect (客户端断连)
                     │
                     ▼
       Flux.doOnCancel(() -> runToken.cancel("Client Disconnected"))
                     │
                     ▼
       ┌───────────────────────────────────────────────┐
       │ RunCancellationToken (Root Scope)             │
       └───────────────────────┬───────────────────────┘
                               │ createChild("node_1")
                               ▼
       ┌───────────────────────────────────────────────┐
       │ NodeCancellationToken (Node Scope)            │
       │ - 超时控制: nodeTimeoutTimer.cancel()          │
       └───────────────────────┬───────────────────────┘
                               │ createChild("agent_screener")
                               ▼
       ┌───────────────────────────────────────────────┐
       │ AgentCancellationToken (Agent Scope)          │
       │ - ReAct 循环前置检查: throwIfCancelled()       │
       └───────────────────────┬───────────────────────┘
                               │ createChild("tool_dpu_sql")
                               ▼
       ┌───────────────────────────────────────────────┐
       │ ToolCancellationToken (Atomic Tool Scope)     │
       │ - 物理中止: OkHttp Call.cancel()              │
       │ - 物理中止: JDBC Statement.cancel()           │
       │ - 物理中止: Vector Search Future.cancel()     │
       └───────────────────────────────────────────────┘
```

#### 5.4.2 级联取消与局部隔离规则
1. **自顶向下级联传播**：父 Token 取消时（如用户断开连接），自动递归触发其下属**所有**子 Token 的 `cancel()`，所有正在运行的虚拟线程收到中断信号 `Thread.interrupt()`，所有注册的回调即刻执行；
2. **自底向上局部隔离**：子 Token 单独取消时（例如某个单项 Tool 发生 HTTP 超时，或单个 Node 达到 `timeoutSeconds`），**默认不扩散至父级和兄弟节点**，仅终止该子任务本身并标记该节点为 `TIMEOUT` / `FAILED`。只有当该节点的 `FailurePolicy == FAIL_FAST` 时，才由 Runtime 主动上浮升级为 Run 级取消；
3. **资源即刻回收保障**：无论因为何种原因取消，挂载在 Token 上的 `ResourceManager` 资源回退钩子保证持有的 `Semaphore` 许可被 100% 归还，绝不产生虚位死锁。

#### 5.4.3 CancellationToken 核心实现契约

```java
package com.financial.copilot.agent.core.dag.runtime.context;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <h1>结构化并发树状取消令牌</h1>
 * <p>支持父子级联传播、虚拟线程中断绑定、底层物理 I/O 资源中止回调与无泄漏清理。</p>
 */
public class CancellationToken {
    private final String scopeId;
    private final CancellationToken parent;
    private final Set<CancellationToken> children = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();
    private final Set<Thread> boundThreads = ConcurrentHashMap.newKeySet();
    private volatile String reason;

    public CancellationToken(String scopeId) {
        this(scopeId, null);
    }

    private CancellationToken(String scopeId, CancellationToken parent) {
        this.scopeId = scopeId;
        this.parent = parent;
    }

    /**
     * 创建受当前生命周期约束的子令牌
     */
    public CancellationToken createChild(String childScopeId) {
        CancellationToken child = new CancellationToken(childScopeId, this);
        if (this.cancelled.get()) {
            child.cancel(this.reason);
        } else {
            children.add(child);
            child.onCancel(() -> children.remove(child));
        }
        return child;
    }

    /**
     * 绑定当前执行虚拟线程，在取消发生时自动 interrupt
     */
    public AutoCloseable bindCurrentThread() {
        Thread t = Thread.currentThread();
        boundThreads.add(t);
        if (cancelled.get()) {
            t.interrupt();
        }
        return () -> boundThreads.remove(t);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public String getReason() {
        return reason;
    }

    public String getScopeId() {
        return scopeId;
    }

    public void throwIfCancelled() throws CancellationException {
        if (isCancelled()) {
            throw new CancellationException("Scope [" + scopeId + "] cancelled: " + reason);
        }
    }

    public void onCancel(Runnable callback) {
        if (cancelled.get()) {
            try {
                callback.run();
            } catch (Exception ignored) {}
        } else {
            callbacks.add(callback);
        }
    }

    public void cancel(String reason) {
        if (cancelled.compareAndSet(false, true)) {
            this.reason = reason;

            // 1. 中断绑定的工作线程
            for (Thread t : boundThreads) {
                try {
                    t.interrupt();
                } catch (Exception ignored) {}
            }
            boundThreads.clear();

            // 2. 执行自身注册的回调 (如释放 Semaphore、取消 Http 连接)
            for (Runnable callback : callbacks) {
                try {
                    callback.run();
                } catch (Exception ignored) {}
            }
            callbacks.clear();

            // 3. 向下树状级联触发全部子作用域
            for (CancellationToken child : children) {
                child.cancel(reason);
            }
            children.clear();
        }
    }
}
```

---

### 5.5 状态快照持久化与断点续跑 (Checkpoint & Resume Protocol)

> **核心原则**：**已成功的节点产物永不重复计算，崩溃或中断后精准原地复活！**  
> 一次完整的深度金融投研通常涉及 10~15 个异构节点：
> * 宏观周期与流动性定调 (LLM + RAG)；
> * 全市场万只基金初筛与分时重仓股穿透 (DPU 大吞吐 SQL)；
> * 多维夏普比率、最大回撤、卡玛比率与风格漂移测算 (数学内核计算)；
> * 基金经理历史能力圈与踩雷排查 (长期记忆 + 新闻舆情)；
> * 深度综合投研研报合成 (长上下文多模态 LLM)。
>
> 整体执行耗时在 30s~90s，消耗数万 Token 与大量 DPU 算力。
> 若执行到第 8 个节点时后端 Pod 发生重启、OOM、网络闪断，或用户主动关闭网页稍后点击“恢复执行”：
> * **无 Checkpoint**：只能从零全部重跑，白白浪费已付出的昂贵 Token、DPU 计算和时间；
> * **有 Checkpoint**：A、B、C 等已成功的节点**绝对不跑**，直接从 `ArtifactStore` 热加载强类型产物，瞬间恢复下游就绪节点执行！

#### 5.5.1 Checkpoint 触发与状态流转

节点成功后，自动触发产物持久化与图状态快照：

```text
    Node Succeeded (节点执行成功)
               │
               ▼
    ArtifactStore.persist(nodeId, artifact)
    (将强类型产物落库/缓存至 Redis)
               │
               ▼
    DagCheckpointStore.saveCheckpoint(checkpoint)
    (记录 RunId, Revision, 节点状态清单, 产物 ID 映射)
               │
               ▼
    DependencyResolver 推进就绪队列
```

#### 5.5.2 断点续跑恢复协议 (Resume Protocol)

当系统重启或接收到 `dagRuntime.resume(runId)` 时：

```text
           dagRuntime.resume(runId)
                      │
                      ▼
        DagCheckpointStore.load(runId)
                      │
        ┌─────────────┴────────────────────────┐
        ▼                                      ▼
[Checkpoint 存在]                     [Checkpoint 不存在]
        │                                      │
        ▼                                      ▼
1. 还原 ExecutionGraph 拓扑结构          报错或退化为全量新运行
2. 批量将状态为 SUCCEEDED 的节点
   置为 SUCCEEDED (A✓, B✓, C✓)
3. 从 ArtifactStore 预热已存产物
4. 将中断前处于 RUNNING 的孤儿节点 (如 D)
   重置为 READY 重新派发 (保证幂等)
5. 遍历 PENDING 节点检查前驱依赖:
   若其所有前驱在 Checkpoint 中已 SUCCEEDED
   -> 立即标记为 READY 并压入 ReadyQueue!
        │
        ▼
   虚拟线程无缝续跑剩余节点 (0 重复开销)
```

#### 5.5.3 核心数据结构与存储契约

```java
package com.financial.copilot.agent.core.dag.runtime.checkpoint;

import com.financial.copilot.agent.core.dag.model.NodeStatus;

import java.time.Instant;
import java.util.Map;

/**
 * <h1>DAG 执行状态持久化快照</h1>
 */
public record DagCheckpoint(
    String runId,
    String sessionId,
    int revision,
    Map<String, NodeStatus> nodeStatuses,
    Map<String, String> artifactIds, // nodeId -> artifactId
    Map<String, Object> serializedPayloads, // 便于跨进程恢复的核心产物反序列化快照
    Instant checkpointTime
) {
    public boolean isNodeCompleted(String nodeId) {
        NodeStatus status = nodeStatuses.get(nodeId);
        return status == NodeStatus.SUCCEEDED || status == NodeStatus.SKIPPED;
    }
}
```

```java
package com.financial.copilot.agent.core.dag.runtime.checkpoint;

import java.util.Optional;

/**
 * <h1>DAG 快照存储适配端口</h1>
 */
public interface DagCheckpointStore {

    /**
     * 保存/更新运行快照
     */
    void saveCheckpoint(DagCheckpoint checkpoint);

    /**
     * 加载指定运行的最新有效快照
     */
    Optional<DagCheckpoint> loadCheckpoint(String runId);

    /**
     * 清除快照（在工作流最终 SUCCEEDED/CANCELLED 后按需归档或清理）
     */
    void clearCheckpoint(String runId);
}
```

* **`InMemoryDagCheckpointStore`**：单机开发与单元测试原生零依赖实现；
* **`RedisDagCheckpointStore`**：生产环境高可用实现，基于工程现存的 `StringRedisTemplate` 存储 JSON 快照，配置 24 小时滑动过期（TTL），天然支持 Pod 漂移与分布式集群断点恢复。

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

---

## 7. 响应式节点级流式协议规范 (Node-Level Reactive SSE Protocol)

> **核心原则**：**彻底摒弃单线递增的 `currentStep / totalSteps`！**  
> 在并行与动态演进的 DAG 中，`3 / 10` 等进度计数在数学上毫无意义且产生严重歧义。
> SSE 必须全面升级为**“节点生命周期与图变更事件流 (Node Lifecycle & Graph Revision Events)”**。

前端基于此协议可直接在画板上渲染真正的动态拓扑图（如 React Flow / AntV X6）：
```text
       ┌── 基金初筛 ✓ (1.2s)
Query ─┼── 宏观分析 ⏳ (运行中)
       └── 市场情绪 ✓ (0.8s)
              │
              ▼
          综合研报合成
```

### 7.1 事件类型与 JSON 契约

#### 1. 图初始化事件 (`graph_initialized`)
流水线启动时推送，包含初始拓扑结构：
```json
{
  "type": "graph_initialized",
  "runId": "run_9a8b7c",
  "revision": 1,
  "nodes": [
    {"nodeId": "fund_screen_1", "name": "医药基金初筛", "taskType": "SCREENING", "parentIds": []},
    {"nodeId": "macro_analysis_1", "name": "宏观流动性定调", "taskType": "MACRO", "parentIds": []},
    {"nodeId": "synthesis_1", "name": "投研报告合成", "taskType": "SYNTHESIS", "parentIds": ["fund_screen_1", "macro_analysis_1"]}
  ]
}
```

#### 2. 节点启动事件 (`node_started`)
任何节点依赖就绪被虚拟线程激活时推送：
```json
{
  "type": "node_started",
  "runId": "run_9a8b7c",
  "nodeId": "fund_screen_1",
  "taskType": "SCREENING",
  "name": "医药基金初筛",
  "parentIds": [],
  "status": "RUNNING",
  "timestamp": 1757651234567
}
```

#### 3. 节点完成事件 (`node_completed`)
节点完成执行并生成产物时推送：
```json
{
  "type": "node_completed",
  "runId": "run_9a8b7c",
  "nodeId": "fund_screen_1",
  "status": "SUCCEEDED",
  "durationMs": 1240,
  "artifactIds": ["art_fund_pool_001"],
  "summary": "初筛命中 5 只医药主题偏股混合基金",
  "components": [
    {"componentType": "COMPARISON_RADAR", "title": "初筛标的多维雷达", "spec": {}}
  ]
}
```

#### 4. 图动态演进事件 (`graph_updated`)
Planner 在 Re-plan Checkpoint 增删节点或变轨时推送，通知前端热更新画布：
```json
{
  "type": "graph_updated",
  "runId": "run_9a8b7c",
  "revision": 2,
  "reason": "初筛命中为0，动态插入放宽门槛重试节点",
  "addedNodes": [
    {"nodeId": "fund_screen_retry", "name": "放宽条件重试初筛", "parentIds": ["fund_screen_1"]}
  ],
  "removedNodes": [],
  "updatedEdges": [
    {"from": "fund_screen_retry", "to": "synthesis_1"}
  ]
}
```

#### 5. 研报生成打字机增量 (`content_chunk`)
终端节点合成正文时的流式 Token：
```json
{
  "type": "content_chunk",
  "runId": "run_9a8b7c",
  "nodeId": "synthesis_1",
  "delta": "综合上述量化数据与宏观定调，建议配置..."
}
```

#### 6. 全图执行结束事件 (`run_completed`)
```json
{
  "type": "run_completed",
  "runId": "run_9a8b7c",
  "status": "SUCCEEDED",
  "totalDurationMs": 5680
}
```

---

## 8. 实施计划与里程碑

```text
┌─────────────────────────────────────────────────────────────────────────┐
│ Milestone 1: 原生图内核与标准产物层 (Core Engine & Artifact)              │
│ - 实现 ExecutionGraph, GraphNode, NodeStatus, Artifact<T>, ArtifactStore│
│ - 实现 ConcurrencyLimiter (基于 Semaphore 的虚拟线程资源配额保护)         │
│ - 实现 DagRuntime (基于 NodeCompletionEvent 的事件驱动图执行模型)         │
│ - 全套单元测试覆盖（菱形依赖、成环检测、并发就绪、Fail-Soft 容错、限流）     │
├─────────────────────────────────────────────────────────────────────────┤
│ Milestone 2: 节点级响应式 SSE 事件与适配器 (Reactive Event & Adapter)    │
│ - 实现 NodeEventBus，输出 graph_initialized, node_started, node_completed│
│ - 实现 LegacyPlanAdapter：现存 ExecutionPlan 平滑转换为 ExecutionGraph    │
│ - 替换 FinancialResearchWorkflow 内的线性循环，升级为并发 DagRuntime     │
├─────────────────────────────────────────────────────────────────────────┤
│ Milestone 3: 四层解耦与 Agent 局部 ReAct (Four-Tier Agent Integration)   │
│ - 统一 Agent 契约：输入强类型 Artifact，输出强类型 Artifact             │
│ - 实现 AnalyzerAgent / ScreenerAgent 局部自省与组件生成能力              │
├─────────────────────────────────────────────────────────────────────────┤
│ Milestone 4: Tool-Augmented Planner & 动态 Re-plan 闭环                  │
│ - 装配 Metric RAG 与 SkillRegistry 给 TaskDecomposer                    │
│ - 实现 RePlanAdvisor，打通中间产物驱动 graph_updated 动态演进自治闭环     │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 9. 验证与验收标准

1. **并发性验证**：当执行多赛道/多标的初筛时（如“半导体 + 新能源”），两个任务的启动时间戳完全重合，总耗时从 $T_1 + T_2$ 骤降至 $\max(T_1, T_2)$；
2. **木桶效应消除验证**：针对长短任务依赖（A=1s -> D, B=5s -> E），D 节点必须在第 1s 准时启动，绝不等待 B 节点；
3. **零回归**：全工程 8 个 Maven 模块现存所有单测保持 100% 通过（`BUILD SUCCESS`）；
4. **纯粹 Java 21**：无任何反射或 Java 17 回退，全链路运行在 Oracle JDK 21.0.12 之上。
