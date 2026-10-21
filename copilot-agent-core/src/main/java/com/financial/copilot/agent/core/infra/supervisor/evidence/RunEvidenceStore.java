package com.financial.copilot.agent.core.infra.supervisor.evidence;

import com.financial.copilot.agent.core.infra.supervisor.agent.AgentRole;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * <h1>运行级金融事实证据仓库 (RunEvidenceStore)</h1>
 * <p>
 * 提供严格按 {@code workflowRunId} 逻辑隔离的追加式 (Append-only) 证据账本存储，
 * 供 Supervisor、质检员与报告合成角色读取不可变事实快照。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class RunEvidenceStore {

    private final Map<String, List<AgentEvidence>> store = new ConcurrentHashMap<>();

    /**
     * 向指定 workflowRunId 追加一条事实证据。
     *
     * @param workflowRunId 工作流执行 ID
     * @param evidence 证据项
     */
    public void append(String workflowRunId, AgentEvidence evidence) {
        Objects.requireNonNull(workflowRunId, "workflowRunId cannot be null");
        Objects.requireNonNull(evidence, "evidence cannot be null");

        store.computeIfAbsent(workflowRunId, k -> new CopyOnWriteArrayList<>()).add(evidence);
    }

    /**
     * 获取指定 workflowRunId 的全部事实证据不可变快照。
     *
     * @param workflowRunId 工作流执行 ID
     * @return 不可变证据列表
     */
    public List<AgentEvidence> getSnapshot(String workflowRunId) {
        if (workflowRunId == null) {
            return List.of();
        }
        List<AgentEvidence> list = store.get(workflowRunId);
        return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    /**
     * 获取指定 workflowRunId 下属于特定角色的证据快照。
     *
     * @param workflowRunId 工作流执行 ID
     * @param role Agent 角色
     * @return 过滤后的不可变证据列表
     */
    public List<AgentEvidence> getRoleSnapshot(String workflowRunId, AgentRole role) {
        if (workflowRunId == null || role == null) {
            return List.of();
        }
        List<AgentEvidence> list = store.get(workflowRunId);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
                .filter(e -> e.role() == role)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 清理指定 Run 的全部证据缓存。
     *
     * @param workflowRunId 工作流执行 ID
     */
    public void clear(String workflowRunId) {
        if (workflowRunId != null) {
            store.remove(workflowRunId);
        }
    }
}
