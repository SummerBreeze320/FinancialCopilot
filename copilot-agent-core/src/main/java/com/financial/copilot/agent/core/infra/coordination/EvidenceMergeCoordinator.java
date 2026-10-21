package com.financial.copilot.agent.core.infra.coordination;

import com.financial.copilot.agent.core.infra.evidence.AgentEvidence;
import com.financial.copilot.agent.core.infra.evidence.RunEvidenceStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <h1>证据版本化归并协调器 (EvidenceMergeCoordinator)</h1>
 * <p>
 * 职责：
 * 1. 跟踪各 Run 的证据修改递增版本号 (Revision)。
 * 2. 在提交合并时校验预期版本，拦截旧版本陈旧读取（防脏读与静默覆盖）。
 * 3. 按照 {@code role -> agentRunId -> evidenceId} 进行确定性归并排序，消除并发执行时因完成顺序差异导致的乱序。
 * 4. 冲突事实一律完整保留，不采用最后写入覆盖，移交风控审核。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class EvidenceMergeCoordinator {

    private static final Comparator<AgentEvidence> DETERMINISTIC_COMPARATOR = Comparator
            .comparing((AgentEvidence e) -> e.role().name())
            .thenComparing(AgentEvidence::agentRunId)
            .thenComparing(AgentEvidence::evidenceId);

    private final RunEvidenceStore evidenceStore;
    private final ConcurrentHashMap<String, AtomicLong> revisions = new ConcurrentHashMap<>();

    public EvidenceMergeCoordinator(RunEvidenceStore evidenceStore) {
        this.evidenceStore = Objects.requireNonNull(evidenceStore, "evidenceStore cannot be null");
    }

    /**
     * 追加事实证据并原子自增版本号
     */
    public void appendEvidence(String workflowRunId, AgentEvidence evidence) {
        Objects.requireNonNull(workflowRunId, "workflowRunId cannot be null");
        Objects.requireNonNull(evidence, "evidence cannot be null");

        evidenceStore.append(workflowRunId, evidence);
        revisions.computeIfAbsent(workflowRunId, k -> new AtomicLong(0L)).incrementAndGet();
    }

    /**
     * 获取指定 Run 的当前证据快照版本号
     */
    public long getCurrentRevision(String workflowRunId) {
        if (workflowRunId == null) {
            return 0L;
        }
        AtomicLong rev = revisions.get(workflowRunId);
        return rev != null ? rev.get() : 0L;
    }

    /**
     * 针对指定版本进行证据归并快照提取
     *
     * @param workflowRunId 工作流执行 ID
     * @param expectedRevision 预期的版本号
     * @return 经过确定性排序的证据不可变快照
     * @throws StaleEvidenceRevisionException 若当前版本已被更新则抛出异常
     */
    public List<AgentEvidence> mergeSnapshot(String workflowRunId, long expectedRevision) {
        Objects.requireNonNull(workflowRunId, "workflowRunId cannot be null");

        long current = getCurrentRevision(workflowRunId);
        if (expectedRevision != current) {
            throw new StaleEvidenceRevisionException(
                    "Stale evidence revision for " + workflowRunId + ": expected=" + expectedRevision + ", actual=" + current
            );
        }

        List<AgentEvidence> raw = evidenceStore.getSnapshot(workflowRunId);
        List<AgentEvidence> sorted = new ArrayList<>(raw);
        sorted.sort(DETERMINISTIC_COMPARATOR);
        return List.copyOf(sorted);
    }
}
