package com.financial.copilot.agent.core.infra.supervisor.coordination;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunStateMachineTest {

    @Test
    void allowsLegalTransitionsFromQueuedToRunningToTerminal() {
        RunStateMachine stateMachine = new RunStateMachine("run-1");

        assertThat(stateMachine.getState()).isEqualTo(RunState.QUEUED);

        stateMachine.transitionTo(RunState.RUNNING);
        assertThat(stateMachine.getState()).isEqualTo(RunState.RUNNING);

        stateMachine.transitionTo(RunState.SUCCEEDED);
        assertThat(stateMachine.getState()).isEqualTo(RunState.SUCCEEDED);
        assertThat(stateMachine.isTerminal()).isTrue();
    }

    @Test
    void rejectsTransitionFromTerminalState() {
        RunStateMachine stateMachine = new RunStateMachine("run-2");
        stateMachine.transitionTo(RunState.RUNNING);
        stateMachine.transitionTo(RunState.CANCELLED);

        assertThatThrownBy(() -> stateMachine.transitionTo(RunState.RUNNING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot transition from terminal state");

        assertThatThrownBy(() -> stateMachine.transitionTo(RunState.SUCCEEDED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot transition from terminal state");
    }

    @Test
    void ignoresLateResultWhenRunIsAlreadyCancelled() {
        RunStateMachine stateMachine = new RunStateMachine("run-3");
        stateMachine.transitionTo(RunState.RUNNING);
        stateMachine.transitionTo(RunState.CANCELLED);

        boolean recorded = stateMachine.recordLateResult("Late agent report");
        // State remains CANCELLED
        assertThat(stateMachine.getState()).isEqualTo(RunState.CANCELLED);
        // Late result is tracked in audit log without changing run state
        assertThat(recorded).isTrue();
        assertThat(stateMachine.getLateAuditResults()).contains("Late agent report");
    }
}
