package com.financial.copilot.agent.core.infra.dag.scheduler;

import com.financial.copilot.agent.core.infra.observability.RuntimeMetrics;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <h1>图运行准入控制器 (Run Admission Controller)</h1>
 * <p>
 * 统一管控多租户、单用户、会话串行化以及全局系统并发与排队准入。
 * 核心机制：
 * <ul>
 *   <li><b>会话串行化隔离 (Conversation Serialization)</b>：每个 (tenantId, userId, conversationId) 同一时刻仅允许一个活跃运行；</li>
 *   <li><b>单用户并发约束 (Per-User Concurrency Limit)</b>：单用户最多允许 maxActiveRunsPerUser 个活跃运行；</li>
 *   <li><b>全局容量与排队上限 (System Queue Ceiling)</b>：超出 maxActiveRuns 入队排队，超出 maxQueuedRuns 触发 503 拒绝保护；</li>
 *   <li><b>排队超时保护 (Queue Timeout)</b>：处于队列中的运行若超时未获调度，自动流转至超时失败终态；</li>
 *   <li><b>非阻塞公平出队</b>：当某个会话运行完成释放时，扫描队列中首个满足会话与用户条件的等待项进行准入。</li>
 * </ul>
 * </p>
 */
@Slf4j
public class RunAdmissionController implements AutoCloseable {

    private final RuntimeCapacityProperties properties;
    private final ScheduledExecutorService timeoutScheduler;
    private final boolean ownsScheduler;
    private final RuntimeMetrics metrics;
    private final ReentrantLock lock = new ReentrantLock();

    /** 全局活跃 Run ID 集合 */
    private final Set<String> activeRunIds = new HashSet<>();

    /** 单用户活跃 Run 映射：(tenantId:userId) -> Set<runId> */
    private final Map<String, Set<String>> activeRunsByUser = new HashMap<>();

    /** 会话唯一活跃锁映射：ConversationKey -> runId */
    private final Map<ConversationKey, String> activeRunByConversation = new HashMap<>();

    /** 队列等待项列表 (保留入队顺序) */
    private final List<QueuedRunEntry> queuedRuns = new LinkedList<>();

    public record ConversationKey(String tenantId, Long userId, UUID conversationId) {
        public ConversationKey {
            if (tenantId == null || tenantId.isBlank()) tenantId = "default";
            if (userId == null) userId = 0L;
            if (conversationId == null) conversationId = new UUID(0L, 0L);
        }
    }

    public record AdmissionTicket(
            AdmissionDecision decision,
            String runId,
            CompletableFuture<AdmissionDecision> onAdmittedFuture,
            int queuePosition
    ) {
        public AdmissionTicket(AdmissionDecision decision, String runId, CompletableFuture<AdmissionDecision> onAdmittedFuture) {
            this(decision, runId, onAdmittedFuture, 0);
        }
    }

    public static class QueuedRunEntry {
        private final String runId;
        private final String tenantId;
        private final Long userId;
        private final UUID conversationId;
        private final Instant queuedAt;
        private final AdmissionDecision queueReason;
        private final CompletableFuture<AdmissionDecision> future;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> timeoutTask;

        public QueuedRunEntry(String runId, String tenantId, Long userId, UUID conversationId,
                              AdmissionDecision queueReason, CompletableFuture<AdmissionDecision> future) {
            this.runId = runId;
            this.tenantId = tenantId;
            this.userId = userId;
            this.conversationId = conversationId;
            this.queuedAt = Instant.now();
            this.queueReason = queueReason;
            this.future = future;
        }

        public String getRunId() { return runId; }
        public String getTenantId() { return tenantId; }
        public Long getUserId() { return userId; }
        public UUID getConversationId() { return conversationId; }
        public Instant getQueuedAt() { return queuedAt; }
        public AdmissionDecision getQueueReason() { return queueReason; }
        public CompletableFuture<AdmissionDecision> getFuture() { return future; }
        public boolean isCancelled() { return cancelled.get(); }
        public void setCancelled() { cancelled.set(true); }
        public void setTimeoutTask(ScheduledFuture<?> task) { this.timeoutTask = task; }
        public void cancelTimeoutTask() {
            ScheduledFuture<?> task = this.timeoutTask;
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    public RunAdmissionController(RuntimeCapacityProperties properties) {
        this(properties, (ScheduledExecutorService) null, null);
    }

    public RunAdmissionController(RuntimeCapacityProperties properties, RuntimeMetrics metrics) {
        this(properties, (ScheduledExecutorService) null, metrics);
    }

    public RunAdmissionController(RuntimeCapacityProperties properties, ScheduledExecutorService timeoutScheduler) {
        this(properties, timeoutScheduler, null);
    }

    public RunAdmissionController(RuntimeCapacityProperties properties, ScheduledExecutorService timeoutScheduler, RuntimeMetrics metrics) {
        this(properties,
                timeoutScheduler != null ? timeoutScheduler : Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "admission-timeout-worker");
                    t.setDaemon(true);
                    return t;
                }),
                timeoutScheduler == null,
                metrics);
    }

    private RunAdmissionController(RuntimeCapacityProperties properties, ScheduledExecutorService timeoutScheduler, boolean ownsScheduler, RuntimeMetrics metrics) {
        this.properties = properties != null ? properties : RuntimeCapacityProperties.defaults();
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler cannot be null");
        this.ownsScheduler = ownsScheduler;
        this.metrics = metrics;
        if (this.metrics != null) {
            this.metrics.bindActiveRunsSupplier(this::getActiveRunCount);
            this.metrics.bindQueuedRunsSupplier(this::getQueuedRunCount);
        }
    }

    /**
     * 获取容量配置属性
     */
    public RuntimeCapacityProperties properties() {
        return properties;
    }

    /**
     * 申请图运行准入凭证
     *
     * @param tenantId       租户标识
     * @param userId         用户 ID
     * @param conversationId 会话 ID
     * @param runId          运行 ID
     * @return 准入凭证，包含即时准入状态及异步准入 Future
     */
    public AdmissionTicket requestAdmission(String tenantId, Long userId, UUID conversationId, String runId) {
        Objects.requireNonNull(runId, "runId cannot be null");
        String effectiveTenant = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
        Long effectiveUser = (userId == null) ? 0L : userId;
        UUID effectiveConversation = (conversationId == null) ? new UUID(0L, 0L) : conversationId;
        ConversationKey convKey = new ConversationKey(effectiveTenant, effectiveUser, effectiveConversation);
        String userKey = effectiveTenant + ":" + effectiveUser;

        lock.lock();
        try {
            // 1. 检查系统全局排队上限
            if (queuedRuns.size() >= properties.getMaxQueuedRuns()) {
                log.warn("System queue capacity exhausted ({} >= {}), rejecting run {}",
                        queuedRuns.size(), properties.getMaxQueuedRuns(), runId);
                CompletableFuture<AdmissionDecision> rejectedFuture = CompletableFuture.failedFuture(
                        new IllegalStateException("System queue capacity exhausted"));
                return new AdmissionTicket(AdmissionDecision.REJECTED_SYSTEM_CAPACITY, runId, rejectedFuture, 0);
            }

            // 2. 检查单用户排队上限
            long userQueued = queuedRuns.stream().filter(e -> !e.isCancelled() && effectiveUser.equals(e.getUserId())).count();
            if (userQueued >= properties.getMaxQueuedRunsPerUser()) {
                log.warn("User {} reached max queued runs ({}/{}), rejecting run {}",
                        effectiveUser, userQueued, properties.getMaxQueuedRunsPerUser(), runId);
                CompletableFuture<AdmissionDecision> rejectedFuture = CompletableFuture.failedFuture(
                        new IllegalStateException("User queue limit exceeded"));
                return new AdmissionTicket(AdmissionDecision.REJECTED_USER_LIMIT, runId, rejectedFuture, 0);
            }

            // 3. 检查会话串行化锁
            if (activeRunByConversation.containsKey(convKey)) {
                log.info("Conversation {} is busy with run {}, queueing run {}",
                        effectiveConversation, activeRunByConversation.get(convKey), runId);
                return enqueueRun(effectiveTenant, effectiveUser, effectiveConversation, runId, AdmissionDecision.QUEUED_CONVERSATION);
            }

            // 4. 检查单用户最大并发运行上限
            Set<String> userRuns = activeRunsByUser.getOrDefault(userKey, Collections.emptySet());
            if (userRuns.size() >= properties.getMaxActiveRunsPerUser()) {
                log.info("User {} reached max active runs ({}/{}), queueing run {}",
                        effectiveUser, userRuns.size(), properties.getMaxActiveRunsPerUser(), runId);
                return enqueueRun(effectiveTenant, effectiveUser, effectiveConversation, runId, AdmissionDecision.QUEUED_USER_LIMIT);
            }

            // 5. 检查系统全局最大活跃运行上限
            if (activeRunIds.size() >= properties.getMaxActiveRuns()) {
                log.info("System reached max active runs ({}/{}), queueing run {}",
                        activeRunIds.size(), properties.getMaxActiveRuns(), runId);
                return enqueueRun(effectiveTenant, effectiveUser, effectiveConversation, runId, AdmissionDecision.QUEUED_USER_LIMIT);
            }

            // 6. 准入成功：登记活跃状态
            activeRunIds.add(runId);
            activeRunsByUser.computeIfAbsent(userKey, k -> new HashSet<>()).add(runId);
            activeRunByConversation.put(convKey, runId);
            log.info("Admitted run {} immediately for user {} (conversation {})", runId, effectiveUser, effectiveConversation);

            return new AdmissionTicket(AdmissionDecision.ADMITTED, runId, CompletableFuture.completedFuture(AdmissionDecision.ADMITTED), 0);
        } finally {
            lock.unlock();
        }
    }

    private AdmissionTicket enqueueRun(String tenantId, Long userId, UUID conversationId, String runId, AdmissionDecision queueReason) {
        CompletableFuture<AdmissionDecision> future = new CompletableFuture<>();
        QueuedRunEntry entry = new QueuedRunEntry(runId, tenantId, userId, conversationId, queueReason, future);

        // 注册超时回调
        Duration timeout = properties.getQueueTimeout();
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            ScheduledFuture<?> timeoutTask = timeoutScheduler.schedule(() -> {
                handleQueueTimeout(entry);
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
            entry.setTimeoutTask(timeoutTask);
        }

        queuedRuns.add(entry);
        int position = queuedRuns.size();
        return new AdmissionTicket(queueReason, runId, future, position);
    }

    private void handleQueueTimeout(QueuedRunEntry entry) {
        lock.lock();
        try {
            if (entry.isCancelled() || entry.getFuture().isDone()) {
                return;
            }
            entry.setCancelled();
            queuedRuns.remove(entry);
            log.warn("Queued run {} timed out after {} in admission queue", entry.getRunId(), properties.getQueueTimeout());
            entry.getFuture().completeExceptionally(new TimeoutException("Queue wait timeout (" + properties.getQueueTimeout() + ") exceeded for run: " + entry.getRunId()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 运行完成（成功、失败或取消终态）时调用，释放配额并驱动队列出队
     *
     * @param runId 运行 ID
     */
    public void onRunCompleted(String runId) {
        if (runId == null) return;
        List<QueuedRunEntry> admittedEntries = new ArrayList<>();

        lock.lock();
        try {
            if (!activeRunIds.remove(runId)) {
                // 如果是排队中的任务被完成/移除
                cancelQueuedRun(runId);
                return;
            }

            // 移除用户活跃映射
            activeRunsByUser.values().forEach(set -> set.remove(runId));
            activeRunsByUser.entrySet().removeIf(e -> e.getValue().isEmpty());

            // 移除会话活跃映射
            activeRunByConversation.entrySet().removeIf(e -> runId.equals(e.getValue()));

            log.info("Released active run {}", runId);

            // 扫描队列，尝试准入等待项
            drainQueue(admittedEntries);
        } finally {
            lock.unlock();
        }

        // 在锁外通知准入成功
        for (QueuedRunEntry entry : admittedEntries) {
            log.info("Dispatched queued run {} after capacity release", entry.getRunId());
            if (metrics != null && entry.getQueuedAt() != null) {
                metrics.recordQueueWait(Duration.between(entry.getQueuedAt(), Instant.now()));
            }
            entry.getFuture().complete(AdmissionDecision.ADMITTED);
        }
    }

    /**
     * 取消排队中的运行
     *
     * @param runId 运行 ID
     * @return 若成功从队列中移除并取消返回 true
     */
    public boolean cancelQueuedRun(String runId) {
        if (runId == null) return false;
        lock.lock();
        try {
            Iterator<QueuedRunEntry> iterator = queuedRuns.iterator();
            while (iterator.hasNext()) {
                QueuedRunEntry entry = iterator.next();
                if (runId.equals(entry.getRunId())) {
                    entry.setCancelled();
                    entry.cancelTimeoutTask();
                    iterator.remove();
                    entry.getFuture().completeExceptionally(new CancellationException("Queued run cancelled: " + runId));
                    log.info("Cancelled and removed queued run {}", runId);
                    return true;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    private void drainQueue(List<QueuedRunEntry> admittedEntries) {
        if (queuedRuns.isEmpty()) return;

        Iterator<QueuedRunEntry> iterator = queuedRuns.iterator();
        while (iterator.hasNext()) {
            if (activeRunIds.size() >= properties.getMaxActiveRuns()) {
                break;
            }

            QueuedRunEntry entry = iterator.next();
            if (entry.isCancelled() || entry.getFuture().isDone()) {
                iterator.remove();
                continue;
            }

            ConversationKey convKey = new ConversationKey(entry.getTenantId(), entry.getUserId(), entry.getConversationId());
            if (activeRunByConversation.containsKey(convKey)) {
                // 该会话仍有未完成的活跃运行，继续等待
                continue;
            }

            String userKey = entry.getTenantId() + ":" + entry.getUserId();
            Set<String> userRuns = activeRunsByUser.getOrDefault(userKey, Collections.emptySet());
            if (userRuns.size() >= properties.getMaxActiveRunsPerUser()) {
                // 该用户仍处于最大活跃运行上限，继续等待
                continue;
            }

            // 满足准入条件：移出队列并登记活跃状态
            iterator.remove();
            entry.cancelTimeoutTask();

            activeRunIds.add(entry.getRunId());
            activeRunsByUser.computeIfAbsent(userKey, k -> new HashSet<>()).add(entry.getRunId());
            activeRunByConversation.put(convKey, entry.getRunId());

            admittedEntries.add(entry);
        }
    }

    /**
     * 获取当前处于活跃运行状态的 Run 总数
     *
     * @return 活跃运行数
     */
    public int getActiveRunCount() {
        lock.lock();
        try {
            return activeRunIds.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取指定用户的活跃运行数
     *
     * @param tenantId 租户标识
     * @param userId   用户 ID
     * @return 用户活跃运行数
     */
    public int getUserActiveRunCount(String tenantId, Long userId) {
        String effectiveTenant = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
        Long effectiveUser = (userId == null) ? 0L : userId;
        String userKey = effectiveTenant + ":" + effectiveUser;
        lock.lock();
        try {
            return activeRunsByUser.getOrDefault(userKey, Collections.emptySet()).size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取当前队列中排队等待的 Run 总数
     *
     * @return 排队运行数
     */
    public int getQueuedRunCount() {
        lock.lock();
        try {
            return queuedRuns.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        if (ownsScheduler) {
            timeoutScheduler.shutdownNow();
        }
    }
}
