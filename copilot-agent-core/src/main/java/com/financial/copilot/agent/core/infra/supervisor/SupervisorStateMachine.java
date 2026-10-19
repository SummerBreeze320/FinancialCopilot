package com.financial.copilot.agent.core.infra.supervisor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>Supervisor 确定性状态机与闭环控制器</h1>
 * <p>
 * 严格控制 PLANNING → EXECUTING → VALIDATING → REVIEWING → AGGREGATING → TERMINAL 状态迁移。
 * 防止审查死循环，追踪重试配额与降级原因。
 * </p>
 *
 * @author FinancialCopilot
 */
public class SupervisorStateMachine {

    private final SupervisorBudget budget;
    private volatile SupervisorState currentState;
    private int reviewCycle = 0;
    private boolean degraded = false;
    private final List<String> degradationReasons = new ArrayList<>();
    private final Map<String, Integer> nodeAttemptCounters = new ConcurrentHashMap<>();

    public SupervisorStateMachine(SupervisorBudget budget) {
        this.budget = budget != null ? budget : SupervisorBudget.defaults();
        this.currentState = SupervisorState.PLANNING;
    }

    public SupervisorState getCurrentState() {
        return currentState;
    }

    public int getReviewCycle() {
        return reviewCycle;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public List<String> getDegradationReasons() {
        return Collections.unmodifiableList(degradationReasons);
    }

    public int getNodeAttempts(String nodeId) {
        return nodeAttemptCounters.getOrDefault(nodeId, 0);
    }

    public void recordNodeAttempt(String nodeId) {
        nodeAttemptCounters.merge(nodeId, 1, Integer::sum);
    }

    public synchronized void transitionTo(SupervisorState nextState) {
        Objects.requireNonNull(nextState, "nextState cannot be null");
        validateTransition(currentState, nextState);
        this.currentState = nextState;
    }

    public synchronized void evaluateReview(ReviewDecision decision) {
        if (currentState != SupervisorState.REVIEWING) {
            throw new IllegalStateException("evaluateReview can only be called in REVIEWING state, currently: " + currentState);
        }
        Objects.requireNonNull(decision, "decision cannot be null");

        switch (decision.action()) {
            case PASS -> {
                this.currentState = SupervisorState.AGGREGATING;
            }
            case RETRY_TASK -> {
                boolean canRetry = reviewCycle < budget.maxReviewCycles();
                for (String taskId : decision.targetTaskIds()) {
                    if (getNodeAttempts(taskId) >= budget.maxNodeAttempts()) {
                        canRetry = false;
                        break;
                    }
                }
                if (canRetry) {
                    reviewCycle++;
                    for (String taskId : decision.targetTaskIds()) {
                        recordNodeAttempt(taskId);
                    }
                    this.currentState = SupervisorState.EXECUTING;
                } else {
                    this.degraded = true;
                    this.degradationReasons.addAll(decision.issues());
                    if (this.degradationReasons.isEmpty()) {
                        this.degradationReasons.add("Retry attempts or cycles exhausted");
                    }
                    this.currentState = SupervisorState.AGGREGATING;
                }
            }
            case REPLAN -> {
                if (reviewCycle < budget.maxReviewCycles()) {
                    reviewCycle++;
                    this.currentState = SupervisorState.EXECUTING;
                } else {
                    this.degraded = true;
                    this.degradationReasons.addAll(decision.issues());
                    if (this.degradationReasons.isEmpty()) {
                        this.degradationReasons.add("Replan cycles exhausted");
                    }
                    this.currentState = SupervisorState.AGGREGATING;
                }
            }
            case DEGRADE -> {
                this.degraded = true;
                this.degradationReasons.addAll(decision.issues());
                this.currentState = SupervisorState.AGGREGATING;
            }
        }
    }

    public synchronized void markSucceeded() {
        if (currentState != SupervisorState.AGGREGATING) {
            throw new IllegalStateException("markSucceeded requires AGGREGATING state, currently: " + currentState);
        }
        this.currentState = degraded ? SupervisorState.DEGRADED : SupervisorState.SUCCEEDED;
    }

    public synchronized void markFailed(String reason) {
        this.degraded = true;
        if (reason != null && !reason.isBlank()) {
            this.degradationReasons.add(reason);
        }
        this.currentState = SupervisorState.FAILED;
    }

    public boolean isTerminal() {
        return currentState == SupervisorState.SUCCEEDED
                || currentState == SupervisorState.DEGRADED
                || currentState == SupervisorState.FAILED;
    }

    private void validateTransition(SupervisorState from, SupervisorState to) {
        if (from == to) return;
        boolean valid = switch (from) {
            case PLANNING -> to == SupervisorState.EXECUTING || to == SupervisorState.FAILED;
            case EXECUTING -> to == SupervisorState.VALIDATING || to == SupervisorState.FAILED;
            case VALIDATING -> to == SupervisorState.REVIEWING || to == SupervisorState.EXECUTING || to == SupervisorState.FAILED;
            case REVIEWING -> to == SupervisorState.AGGREGATING || to == SupervisorState.EXECUTING || to == SupervisorState.FAILED;
            case AGGREGATING -> to == SupervisorState.SUCCEEDED || to == SupervisorState.DEGRADED || to == SupervisorState.FAILED;
            case SUCCEEDED, DEGRADED, FAILED -> false;
        };
        if (!valid) {
            throw new IllegalStateException("Cannot transition from " + from + " to " + to);
        }
    }
}
