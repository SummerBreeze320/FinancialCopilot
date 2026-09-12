package com.financial.copilot.agent.core.dag.runtime.checkpoint;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>内存态 DAG 快照存储实现</h1>
 * 适合单机环境与单元测试，具备零外部中间件依赖与纳秒级读写。
 */
public class InMemoryDagCheckpointStore implements DagCheckpointStore {

    private final Map<String, DagCheckpoint> store = new ConcurrentHashMap<>();

    @Override
    public void saveCheckpoint(DagCheckpoint checkpoint) {
        if (checkpoint != null && checkpoint.runId() != null) {
            store.put(checkpoint.runId(), checkpoint);
        }
    }

    @Override
    public Optional<DagCheckpoint> loadCheckpoint(String runId) {
        if (runId == null) return Optional.empty();
        return Optional.ofNullable(store.get(runId));
    }

    @Override
    public void clearCheckpoint(String runId) {
        if (runId != null) {
            store.remove(runId);
        }
    }
}
