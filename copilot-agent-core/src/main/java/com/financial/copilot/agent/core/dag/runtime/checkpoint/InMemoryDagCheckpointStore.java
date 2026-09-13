package com.financial.copilot.agent.core.dag.runtime.checkpoint;

import com.financial.copilot.agent.core.memory.store.local.BoundedExpiringStore;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * <h1>内存态 DAG 快照存储实现</h1>
 * 适合单机环境与单元测试，具备零外部中间件依赖与纳秒级读写。
 * 24小时 TTL 和最多 1000 个 run 键的 LRU 淘汰。
 */
public class InMemoryDagCheckpointStore implements DagCheckpointStore {

    private record Key(Long userId, String runId) {}
    private static final Duration TTL = Duration.ofHours(24);
    private final BoundedExpiringStore<Key, DagCheckpoint> store;

    public InMemoryDagCheckpointStore() { this(Clock.systemUTC()); }
    public InMemoryDagCheckpointStore(Clock clock) { store = new BoundedExpiringStore<>(clock); }

    @Override
    public void saveCheckpoint(DagCheckpoint checkpoint) {
        if (checkpoint != null && checkpoint.runId() != null) {
            store.update(new Key(checkpoint.userId(), checkpoint.runId()), TTL, old -> checkpoint);
        }
    }

    @Override
    public Optional<DagCheckpoint> load(Long userId, String runId) {
        if (runId == null) return Optional.empty();
        return Optional.ofNullable(store.get(new Key(userId, runId)));
    }

    @Override
    public void clear(Long userId, String runId) {
        if (runId != null) {
            store.remove(new Key(userId, runId));
        }
    }
}
