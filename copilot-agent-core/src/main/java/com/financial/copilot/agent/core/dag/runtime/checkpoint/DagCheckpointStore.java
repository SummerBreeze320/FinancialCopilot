package com.financial.copilot.agent.core.dag.runtime.checkpoint;

import java.util.Optional;

/**
 * <h1>DAG 快照存储端口 (DagCheckpointStore)</h1>
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
     * 清除快照（工作流最终成功或中止后按需清理）
     */
    void clearCheckpoint(String runId);
}
