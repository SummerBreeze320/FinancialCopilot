package com.financial.copilot.agent.core.infra.supervisor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Supervisor 状态机与审查闭环单元测试")
class SupervisorStateMachineTest {

    @Test
    @DisplayName("正常全流程：PLANNING -> EXECUTING -> VALIDATING -> REVIEWING -> PASS -> AGGREGATING -> SUCCEEDED")
    void testNormalLifecyclePass() {
        SupervisorBudget budget = SupervisorBudget.defaults();
        SupervisorStateMachine sm = new SupervisorStateMachine(budget);

        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.PLANNING);

        sm.transitionTo(SupervisorState.EXECUTING);
        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.EXECUTING);

        sm.transitionTo(SupervisorState.VALIDATING);
        sm.transitionTo(SupervisorState.REVIEWING);

        ReviewDecision decision = ReviewDecision.pass();
        sm.evaluateReview(decision);

        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.AGGREGATING);
        assertThat(sm.isDegraded()).isFalse();

        sm.markSucceeded();
        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.SUCCEEDED);
        assertThat(sm.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("目标任务重试：REVIEWING -> RETRY_TASK -> 回到 EXECUTING，递增审查周期与节点重试次数")
    void testRetryTaskWithinBudget() {
        SupervisorBudget budget = new SupervisorBudget(2, 2, Duration.ofMinutes(1), 1000L);
        SupervisorStateMachine sm = new SupervisorStateMachine(budget);

        sm.transitionTo(SupervisorState.EXECUTING);
        sm.transitionTo(SupervisorState.VALIDATING);
        sm.transitionTo(SupervisorState.REVIEWING);

        ReviewDecision retryDecision = ReviewDecision.retry(List.of("node-1"), "数据日期滞后");
        sm.evaluateReview(retryDecision);

        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.EXECUTING);
        assertThat(sm.getReviewCycle()).isEqualTo(1);
        assertThat(sm.getNodeAttempts("node-1")).isEqualTo(1);

        // 第二次 review 再次 retry 相同节点，超过节点单任务最大重试限制，强制转为降级聚合
        sm.transitionTo(SupervisorState.VALIDATING);
        sm.transitionTo(SupervisorState.REVIEWING);

        sm.recordNodeAttempt("node-1"); // attempt 达到 2
        sm.evaluateReview(retryDecision);

        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.AGGREGATING);
        assertThat(sm.isDegraded()).isTrue();
        assertThat(sm.getDegradationReasons()).contains("数据日期滞后");
    }

    @Test
    @DisplayName("局部重规划：REVIEWING -> REPLAN -> 回到 EXECUTING，超过 maxReviewCycles 强制降级")
    void testReplanExceedsMaxCyclesForcesDegrade() {
        SupervisorBudget budget = new SupervisorBudget(1, 2, Duration.ofMinutes(1), 1000L);
        SupervisorStateMachine sm = new SupervisorStateMachine(budget);

        sm.transitionTo(SupervisorState.EXECUTING);
        sm.transitionTo(SupervisorState.VALIDATING);
        sm.transitionTo(SupervisorState.REVIEWING);

        ReviewDecision replanDecision = ReviewDecision.replan("缺失行业比较", List.of("industry_metric"));
        sm.evaluateReview(replanDecision);

        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.EXECUTING);
        assertThat(sm.getReviewCycle()).isEqualTo(1);

        sm.transitionTo(SupervisorState.VALIDATING);
        sm.transitionTo(SupervisorState.REVIEWING);

        // 已经用完 1 次 cycle，再次 replan 强制降级并进入聚合
        sm.evaluateReview(replanDecision);
        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.AGGREGATING);
        assertThat(sm.isDegraded()).isTrue();
    }

    @Test
    @DisplayName("直接 DEGRADE 决策：进入 AGGREGATING 且标记降级")
    void testDirectDegradeDecision() {
        SupervisorBudget budget = SupervisorBudget.defaults();
        SupervisorStateMachine sm = new SupervisorStateMachine(budget);

        sm.transitionTo(SupervisorState.EXECUTING);
        sm.transitionTo(SupervisorState.VALIDATING);
        sm.transitionTo(SupervisorState.REVIEWING);

        ReviewDecision degradeDecision = ReviewDecision.degrade("宏观数据缺失", List.of("缺少利率走势"));
        sm.evaluateReview(degradeDecision);

        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.AGGREGATING);
        assertThat(sm.isDegraded()).isTrue();
        assertThat(sm.getDegradationReasons()).contains("缺少利率走势");

        sm.markSucceeded();
        assertThat(sm.getCurrentState()).isEqualTo(SupervisorState.DEGRADED);
        assertThat(sm.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("非法状态跃迁保护")
    void testInvalidStateTransitions() {
        SupervisorBudget budget = SupervisorBudget.defaults();
        SupervisorStateMachine sm = new SupervisorStateMachine(budget);

        assertThatThrownBy(() -> sm.transitionTo(SupervisorState.REVIEWING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot transition");
    }
}
