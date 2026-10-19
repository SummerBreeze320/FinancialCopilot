package com.financial.copilot.agent.core.infra.dag.scheduler;

import com.financial.copilot.agent.core.infra.dag.runtime.resource.ResourceManager;
import com.financial.copilot.agent.core.infra.dag.runtime.resource.ResourceType;
import com.financial.copilot.agent.core.infra.observability.RuntimeMetrics;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <h1>全局公平就绪节点调度器 (Global Ready Scheduler)</h1>
 * <p>
 * 维护单机全进程统一的就绪节点队列，消除每 Run 独立队列导致的跨 Run 饥饿与卡死现象。
 * 核心调度特性：
 * <ul>
 *   <li><b>分层公平轮询</b>：按 Tenant -> User -> Run 逐级 Round-Robin 轮询，杜绝单个大并发用户占满全量 Agent 资源；</li>
 *   <li><b>动态老化加权优先级</b>：在同一 Run 内部，按静态优先级权重与等待时长老化公式计算动态有效优先级；</li>
 *   <li><b>非队头阻塞 (No Head-of-Line Blocking)</b>：当高优先级节点所需资源未满足时，自动扫描后续可满足的就绪节点进行派发；</li>
 *   <li><b>单 Run Agent 并发上限约束</b>：强行限制单个 Run 占用的最大 Agent 许可数 (maxAgentsPerRun)；</li>
 *   <li><b>事件驱动唤醒</b>：任何节点执行完毕释放物理资源时，自动触发全局调度扫描。</li>
 * </ul>
 * </p>
 */
@Slf4j
public class GlobalReadyScheduler {

    private final ResourceManager resourceManager;
    private final RuntimeCapacityProperties capacityProperties;
    private final ReentrantLock lock = new ReentrantLock();

    /** 结构化待派发就绪节点：TenantId -> (UserId -> (RunId -> List<ReadyNode>)) */
    private final Map<String, Map<Long, Map<String, List<ReadyNode>>>> readyNodes = new LinkedHashMap<>();

    /** 跟踪每个 Run 当前活跃占用的 Agent 许可数 */
    private final Map<String, AtomicInteger> activeAgentsPerRun = new ConcurrentHashMap<>();

    /** Round-Robin 轮询游标 */
    private int tenantCursor = 0;
    private final Map<String, Integer> userCursors = new HashMap<>();
    private final Map<String, Integer> runCursors = new HashMap<>();
    private final RuntimeMetrics metrics;

    public GlobalReadyScheduler(ResourceManager resourceManager, RuntimeCapacityProperties capacityProperties,
                                RuntimeMetrics metrics) {
        this.resourceManager = Objects.requireNonNull(resourceManager, "resourceManager cannot be null");
        this.capacityProperties = capacityProperties != null ? capacityProperties : RuntimeCapacityProperties.defaults();
        this.metrics = metrics;
        this.resourceManager.onResourceReleased(this::schedule);
    }

    public GlobalReadyScheduler(ResourceManager resourceManager, RuntimeCapacityProperties capacityProperties) {
        this(resourceManager, capacityProperties, null);
    }

    /**
     * 将满足依赖的就绪节点提交至全局调度器并触发调度
     *
     * @param node 就绪节点描述
     */
    public void enqueue(ReadyNode node) {
        enqueue(node, true);
    }

    /**
     * 将满足依赖的就绪节点提交至全局调度器
     *
     * @param node            就绪节点描述
     * @param triggerSchedule 是否立即触发调度扫描
     */
    public void enqueue(ReadyNode node, boolean triggerSchedule) {
        if (node == null) return;
        lock.lock();
        try {
            readyNodes.computeIfAbsent(node.tenantId(), t -> new LinkedHashMap<>())
                    .computeIfAbsent(node.userId(), u -> new LinkedHashMap<>())
                    .computeIfAbsent(node.runId(), r -> new ArrayList<>())
                    .add(node);
        } finally {
            lock.unlock();
        }
        if (triggerSchedule) {
            schedule();
        }
    }

    /**
     * 批量将就绪节点提交至全局调度器并在全部入队后触发一次调度扫描
     *
     * @param nodes 就绪节点集合
     */
    public void enqueueAll(Collection<ReadyNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return;
        lock.lock();
        try {
            for (ReadyNode node : nodes) {
                if (node != null) {
                    readyNodes.computeIfAbsent(node.tenantId(), t -> new LinkedHashMap<>())
                            .computeIfAbsent(node.userId(), u -> new LinkedHashMap<>())
                            .computeIfAbsent(node.runId(), r -> new ArrayList<>())
                            .add(node);
                }
            }
        } finally {
            lock.unlock();
        }
        schedule();
    }


    /**
     * 节点执行完成回调，扣减该 Run 活跃 Agent 计数并触发调度
     *
     * @param runId     图运行 ID
     * @param resources 释放的资源规格
     */
    public void onNodeCompleted(String runId, Map<ResourceType, Integer> resources) {
        if (runId != null && resources != null) {
            int agents = resources.getOrDefault(ResourceType.AGENT, 0);
            if (agents > 0) {
                AtomicInteger count = activeAgentsPerRun.get(runId);
                if (count != null) {
                    int remaining = count.addAndGet(-agents);
                    if (remaining <= 0) {
                        activeAgentsPerRun.remove(runId);
                    }
                }
            }
        }
        schedule();
    }

    /**
     * 取消指定 Run 的就绪调度，清除所有待派发节点
     *
     * @param runId 图运行 ID
     */
    public void cancelRun(String runId) {
        if (runId == null) return;
        lock.lock();
        try {
            activeAgentsPerRun.remove(runId);
            for (Map<Long, Map<String, List<ReadyNode>>> usersMap : readyNodes.values()) {
                for (Map<String, List<ReadyNode>> runsMap : usersMap.values()) {
                    runsMap.remove(runId);
                }
            }
            cleanEmpty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 从就绪队列中移除指定节点
     *
     * @param runId  图运行 ID
     * @param nodeId 节点 ID
     */
    public void removeNode(String runId, String nodeId) {
        if (runId == null || nodeId == null) return;
        lock.lock();
        try {
            for (Map<Long, Map<String, List<ReadyNode>>> usersMap : readyNodes.values()) {
                for (Map<String, List<ReadyNode>> runsMap : usersMap.values()) {
                    List<ReadyNode> list = runsMap.get(runId);
                    if (list != null) {
                        list.removeIf(node -> nodeId.equals(node.nodeId()));
                    }
                }
            }
            cleanEmpty();
        } finally {
            lock.unlock();
        }
    }


    /**
     * 触发一轮全局就绪节点调度扫描
     */
    public void schedule() {
        List<ReadyNode> toDispatch = new ArrayList<>();

        lock.lock();
        try {
            boolean dispatchedAny;
            do {
                dispatchedAny = false;
                cleanEmpty();
                if (readyNodes.isEmpty()) {
                    break;
                }

                Instant now = Instant.now();
                List<String> tenants = new ArrayList<>(readyNodes.keySet());
                if (tenants.isEmpty()) break;
                int numTenants = tenants.size();

                for (int t = 0; t < numTenants; t++) {
                    int tenantIdx = (tenantCursor + t) % numTenants;
                    String tenantId = tenants.get(tenantIdx);
                    Map<Long, Map<String, List<ReadyNode>>> usersMap = readyNodes.get(tenantId);
                    if (usersMap == null || usersMap.isEmpty()) continue;

                    List<Long> users = new ArrayList<>(usersMap.keySet());
                    int numUsers = users.size();
                    int userCursor = userCursors.getOrDefault(tenantId, 0);

                    for (int u = 0; u < numUsers; u++) {
                        int userIdx = (userCursor + u) % numUsers;
                        Long userId = users.get(userIdx);
                        Map<String, List<ReadyNode>> runsMap = usersMap.get(userId);
                        if (runsMap == null || runsMap.isEmpty()) continue;

                        String userKey = tenantId + ":" + userId;
                        List<String> runs = new ArrayList<>(runsMap.keySet());
                        int numRuns = runs.size();
                        int runCursor = runCursors.getOrDefault(userKey, 0);

                        for (int r = 0; r < numRuns; r++) {
                            int runIdx = (runCursor + r) % numRuns;
                            String runId = runs.get(runIdx);
                            List<ReadyNode> nodesInRun = runsMap.get(runId);
                            if (nodesInRun == null || nodesInRun.isEmpty()) continue;

                            // 1. 同一 Run 内部，按动态有效优先级从大到小排序
                            nodesInRun.sort((a, b) -> Long.compare(b.effectivePriority(now), a.effectivePriority(now)));

                            ReadyNode selectedNode = null;
                            int selectedIndex = -1;

                            int maxAgentsPerRun = capacityProperties.getMaxAgentsPerRun();
                            int currentAgents = activeAgentsPerRun.computeIfAbsent(runId, k -> new AtomicInteger(0)).get();

                            // 2. 依次扫描候选节点，防止队头资源不足造成队头阻塞
                            for (int i = 0; i < nodesInRun.size(); i++) {
                                ReadyNode candidate = nodesInRun.get(i);
                                if (candidate.cancellationToken() != null && candidate.cancellationToken().isCancelled()) {
                                    if (metrics != null) {
                                        metrics.recordNodeCancelled();
                                    }
                                    nodesInRun.remove(i);
                                    i--;
                                    continue;
                                }

                                int neededAgents = candidate.resources().getOrDefault(ResourceType.AGENT, 0);
                                if (neededAgents > 0 && (currentAgents + neededAgents) > maxAgentsPerRun) {
                                    // 超过单 Run 最大 Agent 并发配额，跳过此节点
                                    continue;
                                }

                                if (resourceManager.tryAcquire(candidate.resources())) {
                                    selectedNode = candidate;
                                    selectedIndex = i;
                                    break;
                                }
                            }

                            if (selectedNode != null) {
                                nodesInRun.remove(selectedIndex);
                                int neededAgents = selectedNode.resources().getOrDefault(ResourceType.AGENT, 0);
                                if (neededAgents > 0) {
                                    activeAgentsPerRun.computeIfAbsent(runId, k -> new AtomicInteger(0)).addAndGet(neededAgents);
                                }

                                // 推进轮询游标
                                tenantCursor = (tenantIdx + 1) % numTenants;
                                userCursors.put(tenantId, (userIdx + 1) % numUsers);
                                runCursors.put(userKey, (runIdx + 1) % numRuns);

                                if (nodesInRun.isEmpty()) {
                                    runsMap.remove(runId);
                                }

                                toDispatch.add(selectedNode);
                                dispatchedAny = true;
                                break; // 成功派发一个节点，跳出当前 Run，开始下一轮 Round-Robin
                            }
                        }
                        if (dispatchedAny) break;
                    }
                    if (dispatchedAny) break;
                }
            } while (dispatchedAny);
        } finally {
            lock.unlock();
        }

        // 在锁外执行派发动作，防止用户回调占用调度锁
        for (ReadyNode readyNode : toDispatch) {
            try {
                if (readyNode.dispatchAction() != null) {
                    readyNode.dispatchAction().run();
                }
            } catch (Exception e) {
                log.error("Failed to dispatch ready node {} for run {}: {}", readyNode.nodeId(), readyNode.runId(), e.getMessage(), e);
            }
        }
    }

    /**
     * 计算节点的有效动态调度优先级
     *
     * @param node 就绪节点
     * @param now  当前时间
     * @return 有效优先级数值
     */
    public long effectivePriority(ReadyNode node, Instant now) {
        if (node == null) return 0L;
        return node.effectivePriority(now);
    }

    /**
     * 获取当前处于等待就绪状态的节点总数
     *
     * @return 待调度就绪节点数
     */
    public int getQueuedNodeCount() {
        lock.lock();
        try {
            int count = 0;
            for (Map<Long, Map<String, List<ReadyNode>>> usersMap : readyNodes.values()) {
                for (Map<String, List<ReadyNode>> runsMap : usersMap.values()) {
                    for (List<ReadyNode> list : runsMap.values()) {
                        count += list.size();
                    }
                }
            }
            return count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取指定 Run 当前活跃执行中的 Agent 数量
     *
     * @param runId 图运行 ID
     * @return 活跃 Agent 许可数
     */
    public int getActiveAgents(String runId) {
        if (runId == null) return 0;
        AtomicInteger count = activeAgentsPerRun.get(runId);
        return count != null ? count.get() : 0;
    }

    private void cleanEmpty() {
        readyNodes.entrySet().removeIf(tEntry -> {
            tEntry.getValue().entrySet().removeIf(uEntry -> {
                uEntry.getValue().entrySet().removeIf(rEntry -> rEntry.getValue().isEmpty());
                return uEntry.getValue().isEmpty();
            });
            return tEntry.getValue().isEmpty();
        });
    }
}
