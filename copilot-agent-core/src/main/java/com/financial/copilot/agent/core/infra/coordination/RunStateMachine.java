package com.financial.copilot.agent.core.infra.coordination;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <h1>工作流运行状态机 (RunStateMachine)</h1>
 * <p>
 * 严格管理单次 Run 的状态迁移：
 * <ul>
 *   <li>QUEUED -> RUNNING -> SUCCEEDED / FAILED / CANCELLED</li>
 *   <li>终态不可逆转，任何试图从终态退回 RUNNING 或其他状态的操作均抛出 {@link IllegalStateException}</li>
 *   <li>Run 取消或失败后，任何迟到的子 Agent 结果只能作为审计信息追加记录，绝不改变 Run 终态与最终报告</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
public class RunStateMachine {

    private final String workflowRunId;
    private final AtomicReference<RunState> state = new AtomicReference<>(RunState.QUEUED);
    private final List<String> lateAuditResults = new CopyOnWriteArrayList<>();

    public RunStateMachine(String workflowRunId) {
        this.workflowRunId = Objects.requireNonNull(workflowRunId, "workflowRunId cannot be null");
    }

    public String getWorkflowRunId() {
        return workflowRunId;
    }

    public RunState getState() {
        return state.get();
    }

    public boolean isTerminal() {
        return state.get().isTerminal();
    }

    /**
     * 执行合法状态跃迁
     *
     * @param newState 目标状态
     * @throws IllegalStateException 若当前已处于终态或跃迁非法
     */
    public void transitionTo(RunState newState) {
        Objects.requireNonNull(newState, "newState cannot be null");

        state.getAndUpdate(current -> {
            if (current == newState) {
                return current;
            }
            if (current.isTerminal()) {
                throw new IllegalStateException("Cannot transition from terminal state " + current + " to " + newState + " for run " + workflowRunId);
            }
            if (current == RunState.QUEUED && newState == RunState.SUCCEEDED) {
                throw new IllegalStateException("Illegal direct transition from QUEUED to SUCCEEDED for run " + workflowRunId);
            }
            return newState;
        });
    }

    /**
     * 记录迟到结果（如已取消或已终结的 Run 收到迟来的 Agent 响应）
     *
     * @param result 迟到的结果摘要或审计载荷
     * @return 是否成功进入迟到审计日志
     */
    public boolean recordLateResult(String result) {
        if (result != null) {
            lateAuditResults.add(result);
            return true;
        }
        return false;
    }

    /**
     * 获取迟到审计结果只读列表
     */
    public List<String> getLateAuditResults() {
        return Collections.unmodifiableList(new ArrayList<>(lateAuditResults));
    }
}
