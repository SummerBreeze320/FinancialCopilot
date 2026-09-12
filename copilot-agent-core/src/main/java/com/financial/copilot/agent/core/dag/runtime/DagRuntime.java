package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactMetadata;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.guard.DefaultNodeQualityGate;
import com.financial.copilot.agent.core.dag.guard.NodeQualityGate;
import com.financial.copilot.agent.core.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.dag.model.ExecutionGraphSnapshot;
import com.financial.copilot.agent.core.dag.model.FailurePolicy;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.model.NodeStatus;
import com.financial.copilot.agent.core.dag.runtime.checkpoint.DagCheckpoint;
import com.financial.copilot.agent.core.dag.runtime.checkpoint.DagCheckpointStore;
import com.financial.copilot.agent.core.dag.runtime.checkpoint.InMemoryDagCheckpointStore;
import com.financial.copilot.agent.core.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.dag.runtime.resource.ResourceManager;
import com.financial.copilot.agent.core.dag.model.patch.GraphOperation;
import com.financial.copilot.agent.core.dag.model.patch.GraphPatch;
import com.financial.copilot.agent.core.dag.model.patch.PatchOp;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * <h1>依赖驱动的事件化 DAG 运行时执行引擎 (DagRuntime)</h1>
 * <p>
 * 基于原生 Java 21 虚拟线程，结合树状结构化取消 (CancellationToken)、声明式多资源限流 (ResourceManager)、
 * 优先级就绪队列 (PriorityReadyQueue)、断点快照持久化 (DagCheckpointStore) 以及三态质量门禁 (NodeQualityGate)。
 * 运行时彻底消除物理 Wavefront 屏障，依赖就绪即按优先级入队并派发。
 * </p>
 */
public class DagRuntime {

    private static final Logger log = LoggerFactory.getLogger(DagRuntime.class);

    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final NodeExecutor nodeExecutor;
    private final ArtifactStore artifactStore;
    private final ResourceManager resourceManager;
    private final DagCheckpointStore checkpointStore;
    private final NodeQualityGate qualityGate;
    private final PriorityReadyQueue readyQueue = new PriorityReadyQueue();
    private final ReplanPolicy replanPolicy;
    private final RePlanAdvisor rePlanAdvisor;
    private final GraphRunRequest runRequest;

    public DagRuntime(NodeExecutor nodeExecutor) {
        this(nodeExecutor, new ArtifactStore(), ResourceManager.defaultManager(), new InMemoryDagCheckpointStore(), new DefaultNodeQualityGate(), ReplanPolicy.heuristic(), null);
    }

    public DagRuntime(
            NodeExecutor nodeExecutor,
            ArtifactStore artifactStore,
            ResourceManager resourceManager,
            DagCheckpointStore checkpointStore,
            NodeQualityGate qualityGate
    ) {
        this(nodeExecutor, artifactStore, resourceManager, checkpointStore, qualityGate, ReplanPolicy.heuristic(), null);
    }

    public DagRuntime(
            NodeExecutor nodeExecutor,
            ArtifactStore artifactStore,
            ResourceManager resourceManager,
            DagCheckpointStore checkpointStore,
            NodeQualityGate qualityGate,
            ReplanPolicy replanPolicy,
            RePlanAdvisor rePlanAdvisor
    ) {
        this(nodeExecutor, artifactStore, resourceManager, checkpointStore, qualityGate, replanPolicy, rePlanAdvisor, null);
    }

    private DagRuntime(NodeExecutor nodeExecutor, ArtifactStore artifactStore, ResourceManager resourceManager,
                       DagCheckpointStore checkpointStore, NodeQualityGate qualityGate, ReplanPolicy replanPolicy,
                       RePlanAdvisor rePlanAdvisor, GraphRunRequest runRequest) {
        this.nodeExecutor = Objects.requireNonNull(nodeExecutor, "nodeExecutor cannot be null");
        this.artifactStore = artifactStore != null ? artifactStore : new ArtifactStore();
        this.resourceManager = resourceManager != null ? resourceManager : ResourceManager.defaultManager();
        this.checkpointStore = checkpointStore != null ? checkpointStore : new InMemoryDagCheckpointStore();
        this.qualityGate = qualityGate != null ? qualityGate : new DefaultNodeQualityGate();
        this.replanPolicy = replanPolicy != null ? replanPolicy : ReplanPolicy.heuristic();
        this.rePlanAdvisor = rePlanAdvisor;
        this.runRequest = runRequest;
    }

    public GraphRunHandle run(GraphRunRequest request, ExecutionGraph graph) {
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(graph, "graph cannot be null");
        DagRunContext context = new DagRunContext(request, graph);

        if (graph.getNodes().isEmpty()) {
            context.events.publishGraphInitialized(request.runId(), graph.getRevision(), List.of());
            context.events.publishRunCompleted(request.runId(), "SUCCEEDED", 0L);
            context.events.complete();
            return new GraphRunHandle(request.runId(), context.events.flux(),
                    CompletableFuture.completedFuture(context.result()), context.cancellation::cancel);
        }

        DagRuntime isolated = new DagRuntime(nodeExecutor, context.artifacts, resourceManager,
                checkpointStore, qualityGate, replanPolicy, rePlanAdvisor, request);
        CompletableFuture<GraphRunResult> completion = new CompletableFuture<>();
        context.events.publishGraphInitialized(request.runId(), graph.getRevision(), graph.getNodes().values().stream()
                .map(node -> new com.financial.copilot.agent.core.dag.event.NodeEventBus.NodeDescriptor(
                        node.getNodeId(), node.getName(), node.getTaskType(), List.copyOf(graph.getUpstream(node.getNodeId()))))
                .toList());
        isolated.executeGraph(request.runId(), graph, context.artifacts, context.cancellation, event -> {
            if (!(event.payload() instanceof GraphPatch)) {
                context.statuses.put(event.nodeId(), event.status());
            }
            context.events.publishDagEvent(request.runId(), graph, event);
        }).whenComplete((ignored, error) -> {
            isolated.virtualThreadExecutor.shutdown();
            if (error != null) {
                if (error instanceof CancellationException || error.getCause() instanceof CancellationException) {
                    context.events.emit(com.financial.copilot.common.event.ResearchStreamEvent.runCancelled(request.runId(), error.getMessage()));
                } else {
                    context.events.emit(com.financial.copilot.common.event.ResearchStreamEvent.runFailed(request.runId(), error.getMessage()));
                }
                context.events.complete();
                completion.completeExceptionally(error);
            } else {
                context.statuses.keySet().retainAll(graph.getNodes().keySet());
                GraphRunResult result = context.result();
                context.events.publishRunCompleted(request.runId(), "SUCCEEDED",
                        java.time.Duration.between(result.startedAt(), result.completedAt()).toMillis());
                context.events.complete();
                completion.complete(result);
            }
        });
        return new GraphRunHandle(request.runId(), context.events.flux(), completion, context.cancellation::cancel);
    }

    public CompletableFuture<Void> executeGraph(
            ExecutionGraph graph,
            CancellationToken cancellationToken,
            Consumer<DagEvent> eventPublisher
    ) {
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        return executeGraph(runId, graph, this.artifactStore, cancellationToken, eventPublisher);
    }

    public CompletableFuture<Void> executeGraph(
            String runId,
            ExecutionGraph graph,
            CancellationToken cancellationToken,
            Consumer<DagEvent> eventPublisher
    ) {
        return executeGraph(runId, graph, this.artifactStore, cancellationToken, eventPublisher);
    }

    public CompletableFuture<Void> executeGraph(
            String runId,
            ExecutionGraph graph,
            ArtifactStore customStore,
            CancellationToken cancellationToken,
            Consumer<DagEvent> eventPublisher
    ) {
        Objects.requireNonNull(graph, "graph cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        ArtifactStore effectiveStore = customStore != null ? customStore : this.artifactStore;
        Consumer<DagEvent> publisher = eventPublisher != null ? eventPublisher : e -> {};

        CompletableFuture<Void> graphFuture = new CompletableFuture<>();
        Map<String, AtomicReference<NodeStatus>> statusMap = new ConcurrentHashMap<>();
        AtomicInteger activeOrPendingNodes = new AtomicInteger(0);

        // 注册取消处理钩子
        cancellationToken.onCancel(() -> {
            if (!graphFuture.isDone()) {
                graphFuture.completeExceptionally(new CancellationException("Run [" + runId + "] cancelled: " + cancellationToken.getReason()));
            }
            for (Map.Entry<String, AtomicReference<NodeStatus>> entry : statusMap.entrySet()) {
                entry.getValue().compareAndSet(NodeStatus.PENDING, NodeStatus.CANCELLED);
                entry.getValue().compareAndSet(NodeStatus.READY, NodeStatus.CANCELLED);
            }
        });

        // 1. 初始化所有节点为 PENDING
        for (String nodeId : graph.getNodes().keySet()) {
            statusMap.put(nodeId, new AtomicReference<>(NodeStatus.PENDING));
            activeOrPendingNodes.incrementAndGet();
        }

        // 2. 将所有入度为 0 的根节点压入优先级就绪队列并调度
        Set<String> rootNodeIds = graph.getRootNodeIds();
        for (String rootId : rootNodeIds) {
            enqueueReadyNode(rootId, graph, statusMap, publisher);
        }
        drainReadyQueue(runId, graph, effectiveStore, statusMap, activeOrPendingNodes, graphFuture, cancellationToken, publisher);

        return graphFuture;
    }

    public CompletableFuture<Void> resume(
            String runId,
            ExecutionGraph graph,
            CancellationToken cancellationToken,
            Consumer<DagEvent> eventPublisher
    ) {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(graph, "graph cannot be null");
        Consumer<DagEvent> publisher = eventPublisher != null ? eventPublisher : e -> {};

        Optional<DagCheckpoint> checkpointOpt = checkpointStore.loadCheckpoint(runId);
        if (checkpointOpt.isEmpty()) {
            log.warn("No checkpoint found for runId={}, falling back to clean run", runId);
            return executeGraph(runId, graph, cancellationToken, publisher);
        }

        DagCheckpoint checkpoint = checkpointOpt.get();
        CompletableFuture<Void> graphFuture = new CompletableFuture<>();
        Map<String, AtomicReference<NodeStatus>> statusMap = new ConcurrentHashMap<>();
        AtomicInteger activeOrPendingNodes = new AtomicInteger(0);

        cancellationToken.onCancel(() -> {
            if (!graphFuture.isDone()) {
                graphFuture.completeExceptionally(new CancellationException("Resume [" + runId + "] cancelled: " + cancellationToken.getReason()));
            }
        });

        // 1. 恢复快照状态
        for (String nodeId : graph.getNodes().keySet()) {
            NodeStatus historicalStatus = checkpoint.nodeStatuses().get(nodeId);
            if (historicalStatus == NodeStatus.SUCCEEDED || historicalStatus == NodeStatus.SKIPPED) {
                // 已完成的节点无需重复计算，直接标记完成
                statusMap.put(nodeId, new AtomicReference<>(historicalStatus));
                publisher.accept(new DagEvent(nodeId, historicalStatus, "Restored from checkpoint (skipped re-execution)"));
            } else {
                statusMap.put(nodeId, new AtomicReference<>(NodeStatus.PENDING));
                activeOrPendingNodes.incrementAndGet();
            }
        }

        if (activeOrPendingNodes.get() == 0) {
            graphFuture.complete(null);
            return graphFuture;
        }

        // 2. 找出当前所有依赖已满足的未完成节点并推入优先级就绪队列
        for (String nodeId : graph.getNodes().keySet()) {
            if (statusMap.get(nodeId).get() == NodeStatus.PENDING) {
                if (DependencyResolver.isReady(nodeId, graph, id -> statusMap.get(id) != null ? statusMap.get(id).get() : null)) {
                    enqueueReadyNode(nodeId, graph, statusMap, publisher);
                }
            }
        }
        drainReadyQueue(runId, graph, this.artifactStore, statusMap, activeOrPendingNodes, graphFuture, cancellationToken, publisher);

        return graphFuture;
    }

    /** Restores an owned run from durable state in a fresh runtime instance. */
    public GraphRunHandle resume(Long userId, String runId,
                                 Consumer<com.financial.copilot.agent.core.llm.dto.LlmResponse> usageConsumer) {
        Optional<DagCheckpoint> checkpoint = checkpointStore.load(userId, runId);
        if (checkpoint.isEmpty() || checkpoint.get().graph() == null) {
            CompletableFuture<GraphRunResult> missing = CompletableFuture.failedFuture(
                    new NoSuchElementException("No checkpoint for owned run: " + runId));
            return new GraphRunHandle(runId, reactor.core.publisher.Flux.empty(), missing, ignored -> {});
        }
        DagCheckpoint saved = checkpoint.get();
        ExecutionGraph graph = saved.graph().restore();
        GraphRunRequest request = new GraphRunRequest(runId, userId, saved.sessionId(), "", false,
                null, usageConsumer, RunMode.SYNC);
        DagRunContext context = new DagRunContext(request, graph);
        saved.artifacts().forEach(context.artifacts::store);
        saved.nodeStatuses().forEach((id, status) -> context.statuses.put(id, normalizeRestoredStatus(status)));

        DagRuntime isolated = new DagRuntime(nodeExecutor, context.artifacts, resourceManager,
                checkpointStore, qualityGate, replanPolicy, rePlanAdvisor, request);
        CompletableFuture<GraphRunResult> completion = new CompletableFuture<>();
        isolated.resumeFromCheckpoint(saved, graph, context.artifacts, context.cancellation, event -> {
            context.statuses.put(event.nodeId(), event.status());
        }).whenComplete((ignored, error) -> {
            isolated.virtualThreadExecutor.shutdown();
            if (error != null) completion.completeExceptionally(error);
            else completion.complete(context.result());
        });
        return new GraphRunHandle(runId, context.events.flux(), completion, context.cancellation::cancel);
    }

    private CompletableFuture<Void> resumeFromCheckpoint(
            DagCheckpoint checkpoint, ExecutionGraph graph, ArtifactStore store,
            CancellationToken token, Consumer<DagEvent> publisher) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Map<String, AtomicReference<NodeStatus>> statuses = new ConcurrentHashMap<>();
        AtomicInteger active = new AtomicInteger();
        token.onCancel(() -> future.completeExceptionally(
                new CancellationException("Resume cancelled: " + token.getReason())));

        for (String nodeId : graph.getNodes().keySet()) {
            NodeStatus restored = normalizeRestoredStatus(checkpoint.nodeStatuses().get(nodeId));
            statuses.put(nodeId, new AtomicReference<>(restored));
            if (restored == NodeStatus.SUCCEEDED || restored == NodeStatus.SKIPPED) {
                publisher.accept(new DagEvent(nodeId, restored, "Restored from checkpoint"));
            } else {
                statuses.get(nodeId).set(NodeStatus.PENDING);
                active.incrementAndGet();
            }
        }
        if (active.get() == 0) {
            future.complete(null);
            return future;
        }
        for (String nodeId : graph.getNodes().keySet()) {
            if (statuses.get(nodeId).get() == NodeStatus.PENDING
                    && DependencyResolver.isReady(nodeId, graph,
                    id -> statuses.get(id) == null ? null : statuses.get(id).get())) {
                enqueueReadyNode(nodeId, graph, statuses, publisher);
            }
        }
        drainReadyQueue(checkpoint.runId(), graph, store, statuses, active, future, token, publisher);
        return future;
    }

    private static NodeStatus normalizeRestoredStatus(NodeStatus status) {
        if (status == NodeStatus.SUCCEEDED || status == NodeStatus.SKIPPED) return status;
        return NodeStatus.PENDING;
    }

    private void enqueueReadyNode(
            String nodeId,
            ExecutionGraph graph,
            Map<String, AtomicReference<NodeStatus>> statusMap,
            Consumer<DagEvent> publisher
    ) {
        AtomicReference<NodeStatus> statusRef = statusMap.get(nodeId);
        if (statusRef == null || !statusRef.compareAndSet(NodeStatus.PENDING, NodeStatus.READY)) {
            return;
        }

        GraphNode node = graph.getNode(nodeId);
        if (node != null) {
            publisher.accept(new DagEvent(nodeId, NodeStatus.READY, "Node is ready in priority queue"));
            readyQueue.offer(node);
        }
    }

    private void submitToVirtualThread(
            GraphNode node,
            String runId,
            ExecutionGraph graph,
            ArtifactStore currentStore,
            Map<String, AtomicReference<NodeStatus>> statusMap,
            AtomicInteger activeOrPendingNodes,
            CompletableFuture<Void> graphFuture,
            CancellationToken parentToken,
            Consumer<DagEvent> publisher
    ) {
        String nodeId = node.getNodeId();
        CancellationToken nodeToken = parentToken.createChild(nodeId);

        virtualThreadExecutor.submit(() -> {
            try (AutoCloseable ignored = nodeToken.bindCurrentThread()) {
                if (nodeToken.isCancelled() || graphFuture.isDone()) {
                    return;
                }

                statusMap.get(nodeId).set(NodeStatus.RUNNING);
                publisher.accept(new DagEvent(nodeId, NodeStatus.RUNNING, "Node execution started"));

                Artifact<?> result = executeWithTimeout(node, currentStore, nodeToken);

                // 节点质量门禁三态裁决
                NodeQualityGate.GateVerdict verdict = qualityGate.evaluate(node, result, graph);
                switch (verdict.decision()) {
                    case PASS -> {
                        currentStore.store(nodeId, result);
                        statusMap.get(nodeId).set(NodeStatus.SUCCEEDED);
                        saveRunCheckpoint(runId, graph, currentStore, statusMap);
                        onNodeCompleted(nodeId, NodeStatus.SUCCEEDED, result, null, runId, graph, currentStore, statusMap, activeOrPendingNodes, graphFuture, parentToken, publisher);
                    }
                    case NEED_MORE_DATA -> {
                        // 缺少数据时仍存入当前产物并放行，由后续规划或报告体现
                        currentStore.store(nodeId, result);
                        statusMap.get(nodeId).set(NodeStatus.SUCCEEDED);
                        saveRunCheckpoint(runId, graph, currentStore, statusMap);
                        publisher.accept(new DagEvent(nodeId, NodeStatus.SUCCEEDED, "Gate: NEED_MORE_DATA - " + verdict.reason(), result));
                        onNodeCompleted(nodeId, NodeStatus.SUCCEEDED, result, null, runId, graph, currentStore, statusMap, activeOrPendingNodes, graphFuture, parentToken, publisher);
                    }
                    case INVALID -> {
                        handleNodeFailure(node, new IllegalStateException("Gate rejected artifact: " + verdict.reason()),
                                runId, graph, currentStore, statusMap, activeOrPendingNodes, graphFuture, parentToken, publisher);
                    }
                }
            } catch (TimeoutException e) {
                FailurePolicy policy = node.getFailurePolicy() != null
                        ? node.getFailurePolicy() : FailurePolicy.CONTINUE;
                if (policy == FailurePolicy.CONTINUE) {
                    statusMap.get(nodeId).set(NodeStatus.TIMEOUT);
                    saveRunCheckpoint(runId, graph, currentStore, statusMap);
                    onNodeCompleted(nodeId, NodeStatus.TIMEOUT, null, e, runId, graph,
                            currentStore, statusMap, activeOrPendingNodes, graphFuture, parentToken, publisher);
                } else {
                    handleNodeFailure(node, e, runId, graph, currentStore, statusMap,
                            activeOrPendingNodes, graphFuture, parentToken, publisher);
                }
            } catch (Exception e) {
                if (!nodeToken.isCancelled() && !parentToken.isCancelled() && !graphFuture.isDone()) {
                    handleNodeFailure(node, e, runId, graph, currentStore, statusMap, activeOrPendingNodes, graphFuture, parentToken, publisher);
                }
            } finally {
                // 释放物理配额并唤醒队列中等待的就绪节点
                resourceManager.release(node.getResourceRequirements());
                drainReadyQueue(runId, graph, currentStore, statusMap, activeOrPendingNodes, graphFuture, parentToken, publisher);
            }
        });
    }

    private Artifact<?> executeWithTimeout(GraphNode node, ArtifactStore store,
                                            CancellationToken token) throws Exception {
        Future<Artifact<?>> future = virtualThreadExecutor.submit(() -> {
            try (AutoCloseable ignored = token.bindCurrentThread()) {
                NodeInput input = DependencyResolver.resolve(node, store);
                return nodeExecutor.execute(node, input, new NodeExecutionContext(runRequest, store, token));
            }
        });
        try {
            return future.get(node.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException(cause);
        } catch (TimeoutException e) {
            token.cancel("Node timeout: " + node.getNodeId());
            future.cancel(true);
            throw e;
        }
    }

    private void handleNodeFailure(
            GraphNode node,
            Exception e,
            String runId,
            ExecutionGraph graph,
            ArtifactStore currentStore,
            Map<String, AtomicReference<NodeStatus>> statusMap,
            AtomicInteger activeOrPendingNodes,
            CompletableFuture<Void> graphFuture,
            CancellationToken parentToken,
            Consumer<DagEvent> publisher
    ) {
        String nodeId = node.getNodeId();
        FailurePolicy policy = node.getFailurePolicy() != null ? node.getFailurePolicy() : FailurePolicy.CONTINUE;

        switch (policy) {
            case RETRY -> {
                if (node.getRetryCount() < node.getMaxRetries()) {
                    node.incrementRetryCount();
                    log.warn("Node {} failed (attempt {}/{}), retrying: {}", nodeId, node.getRetryCount(), node.getMaxRetries(), e.getMessage());
                    statusMap.get(nodeId).set(NodeStatus.PENDING);
                    enqueueReadyNode(nodeId, graph, statusMap, publisher);
                    drainReadyQueue(runId, graph, currentStore, statusMap, activeOrPendingNodes, graphFuture, parentToken, publisher);
                } else {
                    log.error("Node {} retries exhausted, failing fast: {}", nodeId, e.getMessage());
                    failFast(nodeId, e, runId, graph, statusMap, graphFuture, parentToken, publisher);
                }
            }
            case FALLBACK -> {
                Object fallbackPayload = node.getFallbackProvider() != null
                        ? node.getFallbackProvider().provideFallback(node)
                        : "Fallback due to: " + e.getMessage();
                Artifact<?> fallbackArt = Artifact.of(
                        "art_fb_" + nodeId,
                        node.getOutputType() != null ? node.getOutputType() : ArtifactType.GENERAL,
                        nodeId,
                        fallbackPayload,
                        ArtifactMetadata.partial("FALLBACK", List.of(), e.getMessage())
                );
                currentStore.store(nodeId, fallbackArt);
                statusMap.get(nodeId).set(NodeStatus.SUCCEEDED);
                log.info("Node {} executed fallback successfully", nodeId);
                saveRunCheckpoint(runId, graph, currentStore, statusMap);
                onNodeCompleted(nodeId, NodeStatus.SUCCEEDED, fallbackArt, null, runId, graph, currentStore, statusMap, activeOrPendingNodes, graphFuture, parentToken, publisher);
            }
            case CONTINUE -> {
                log.warn("Node {} failed with policy CONTINUE, bypassing downstream blocking: {}", nodeId, e.getMessage());
                Artifact<?> degradedArt = Artifact.of(
                        "art_degraded_" + nodeId,
                        node.getOutputType() != null ? node.getOutputType() : ArtifactType.GENERAL,
                        nodeId,
                        "Degraded: " + e.getMessage(),
                        ArtifactMetadata.partial("DEGRADED", List.of(), e.getMessage())
                );
                currentStore.store(nodeId, degradedArt);
                statusMap.get(nodeId).set(NodeStatus.SUCCEEDED);
                saveRunCheckpoint(runId, graph, currentStore, statusMap);
                onNodeCompleted(nodeId, NodeStatus.SUCCEEDED, degradedArt, null, runId, graph, currentStore, statusMap, activeOrPendingNodes, graphFuture, parentToken, publisher);
            }
            case OPTIONAL -> {
                log.info("Optional node {} failed, marking SKIPPED and proceeding: {}", nodeId, e.getMessage());
                statusMap.get(nodeId).set(NodeStatus.SKIPPED);
                saveRunCheckpoint(runId, graph, currentStore, statusMap);
                onNodeCompleted(nodeId, NodeStatus.SKIPPED, null, null, runId, graph, currentStore, statusMap, activeOrPendingNodes, graphFuture, parentToken, publisher);
            }
            case FAIL_FAST -> {
                log.error("Node {} failed under FAIL_FAST, aborting graph: {}", nodeId, e.getMessage());
                failFast(nodeId, e, runId, graph, statusMap, graphFuture, parentToken, publisher);
            }
        }
    }

    private void failFast(
            String nodeId,
            Throwable e,
            String runId,
            ExecutionGraph graph,
            Map<String, AtomicReference<NodeStatus>> statusMap,
            CompletableFuture<Void> graphFuture,
            CancellationToken parentToken,
            Consumer<DagEvent> publisher
    ) {
        statusMap.get(nodeId).set(NodeStatus.FAILED);
        publisher.accept(new DagEvent(nodeId, NodeStatus.FAILED, "Node failed: " + e.getMessage()));
        saveRunCheckpoint(runId, graph, this.artifactStore, statusMap);
        graphFuture.completeExceptionally(e);
        parentToken.cancel("FAIL_FAST triggered by node: " + nodeId);
    }

    private void onNodeCompleted(
            String completedNodeId,
            NodeStatus finalStatus,
            Artifact<?> result,
            Throwable error,
            String runId,
            ExecutionGraph graph,
            ArtifactStore currentStore,
            Map<String, AtomicReference<NodeStatus>> statusMap,
            AtomicInteger activeOrPendingNodes,
            CompletableFuture<Void> graphFuture,
            CancellationToken parentToken,
            Consumer<DagEvent> publisher
    ) {
        statusMap.get(completedNodeId).set(finalStatus);
        publisher.accept(new DagEvent(
                completedNodeId,
                finalStatus,
                finalStatus == NodeStatus.SUCCEEDED ? "Node succeeded" : "Node skipped/failed",
                result
        ));

        // 动态改图与自适应变轨 (RePlanAdvisor)
        if (rePlanAdvisor != null && replanPolicy != null && replanPolicy.shouldReplan(graph, completedNodeId, result, finalStatus)) {
            try {
                GraphPatch patch = rePlanAdvisor.planPatch(graph, completedNodeId, result);
                if (patch != null && !patch.operations().isEmpty()) {
                    validatePatchLifecycle(patch, statusMap);
                    int newRev = graph.applyPatch(patch, id -> {
                        AtomicReference<NodeStatus> reference = statusMap.get(id);
                        if (reference == null) return true;
                        NodeStatus status = reference.get();
                        return status == NodeStatus.PENDING || status == NodeStatus.READY
                                || status == NodeStatus.FAILED || status == NodeStatus.TIMEOUT;
                    });
                    log.info("Applied GraphPatch to graph {} (new revision={}), operations count={}", graph.getGraphId(), newRev, patch.operations().size());

                    reconcilePatch(patch, graph, statusMap, activeOrPendingNodes, publisher);
                    publisher.accept(new DagEvent(completedNodeId, NodeStatus.RUNNING, "Graph patched to rev " + newRev, patch));
                }
            } catch (Exception patchEx) {
                log.error("Failed to plan/apply GraphPatch for completedNode {}: {}", completedNodeId, patchEx.getMessage(), patchEx);
            }
        }

        // 寻找满足全部依赖的下游直接子节点，放入优先级就绪队列
        List<String> readyChildren = DependencyResolver.findReadyChildren(
                completedNodeId,
                graph,
                id -> statusMap.get(id) != null ? statusMap.get(id).get() : null
        );

        for (String childId : readyChildren) {
            enqueueReadyNode(childId, graph, statusMap, publisher);
        }
        drainReadyQueue(runId, graph, currentStore, statusMap, activeOrPendingNodes, graphFuture, parentToken, publisher);

        // 计数递减，归零则图整体完成
        if (activeOrPendingNodes.decrementAndGet() == 0) {
            graphFuture.complete(null);
        }
    }

    private void validatePatchLifecycle(GraphPatch patch, Map<String, AtomicReference<NodeStatus>> statuses) {
        for (GraphOperation op : patch.operations()) {
            if (op.op() == PatchOp.ADD_NODE || op.op() == PatchOp.ADD_EDGE || op.op() == PatchOp.REMOVE_EDGE) continue;
            AtomicReference<NodeStatus> reference = statuses.get(op.nodeId());
            if (reference == null) continue;
            NodeStatus status = reference.get();
            if (op.op() == PatchOp.RETRY_NODE) {
                if (status != NodeStatus.FAILED && status != NodeStatus.TIMEOUT) {
                    throw new IllegalStateException("Only FAILED or TIMEOUT nodes can be retried: " + op.nodeId());
                }
            } else if (status != NodeStatus.PENDING && status != NodeStatus.READY) {
                throw new IllegalStateException("Node is not mutable: " + op.nodeId());
            }
        }
    }

    private void reconcilePatch(GraphPatch patch, ExecutionGraph graph,
                                Map<String, AtomicReference<NodeStatus>> statuses,
                                AtomicInteger activeOrPendingNodes, Consumer<DagEvent> publisher) {
        for (GraphOperation op : patch.operations()) {
            switch (op.op()) {
                case ADD_NODE -> {
                    statuses.put(op.nodeId(), new AtomicReference<>(NodeStatus.PENDING));
                    activeOrPendingNodes.incrementAndGet();
                }
                case REMOVE_NODE -> {
                    if (statuses.remove(op.nodeId()) != null) activeOrPendingNodes.decrementAndGet();
                }
                case SKIP_NODE -> {
                    AtomicReference<NodeStatus> status = statuses.get(op.nodeId());
                    if (status != null) {
                        status.set(NodeStatus.SKIPPED);
                        activeOrPendingNodes.decrementAndGet();
                        publisher.accept(new DagEvent(op.nodeId(), NodeStatus.SKIPPED, "Node skipped by graph patch"));
                    }
                }
                case RETRY_NODE -> {
                    AtomicReference<NodeStatus> status = statuses.get(op.nodeId());
                    if (status != null) {
                        status.set(NodeStatus.PENDING);
                        activeOrPendingNodes.incrementAndGet();
                    }
                }
                default -> { }
            }
        }
        graph.getNodes().keySet().stream()
                .filter(id -> statuses.get(id) != null && statuses.get(id).get() == NodeStatus.PENDING)
                .filter(id -> DependencyResolver.isReady(id, graph,
                        key -> statuses.get(key) != null ? statuses.get(key).get() : null))
                .forEach(id -> enqueueReadyNode(id, graph, statuses, publisher));
    }

    private synchronized void drainReadyQueue(
            String runId,
            ExecutionGraph graph,
            ArtifactStore currentStore,
            Map<String, AtomicReference<NodeStatus>> statusMap,
            AtomicInteger activeOrPendingNodes,
            CompletableFuture<Void> graphFuture,
            CancellationToken parentToken,
            Consumer<DagEvent> publisher
    ) {
        if (parentToken.isCancelled() || graphFuture.isDone()) {
            return;
        }

        while (!readyQueue.isEmpty()) {
            GraphNode nextNode = readyQueue.peek();
            if (nextNode == null) break;

            if (resourceManager.tryAcquire(nextNode.getResourceRequirements())) {
                readyQueue.poll();
                submitToVirtualThread(nextNode, runId, graph, currentStore, statusMap, activeOrPendingNodes, graphFuture, parentToken, publisher);
            } else {
                // 信号量不足，等待后续释放唤醒
                break;
            }
        }
    }

    private void saveRunCheckpoint(String runId, ExecutionGraph graph, ArtifactStore currentStore, Map<String, AtomicReference<NodeStatus>> statusMap) {
        try {
            Map<String, NodeStatus> snapshot = new HashMap<>();
            statusMap.forEach((k, v) -> snapshot.put(k, v.get()));

            DagCheckpoint cp = new DagCheckpoint(
                    runId,
                    runRequest != null ? runRequest.userId() : null,
                    runRequest != null ? runRequest.sessionId() : graph.getGraphId(),
                    ExecutionGraphSnapshot.from(graph),
                    snapshot,
                    currentStore.getAllArtifacts(),
                    Instant.now()
            );
            checkpointStore.saveCheckpoint(cp);
        } catch (Exception e) {
            log.warn("Failed to save DagCheckpoint for runId={}: {}", runId, e.getMessage());
        }
    }
}
