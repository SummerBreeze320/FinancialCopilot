package com.financial.copilot.agent.core.infra.dag.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunAdmissionControllerTest {

    @Test
    @DisplayName("会话并发测试：同一用户在限额内不同会话可并发准入")
    void differentConversationsForOneUserCanRunConcurrentlyWithinUserLimit() {
        RuntimeCapacityProperties props = RuntimeCapacityProperties.builder()
                .maxActiveRunsPerUser(2)
                .build();
        try (RunAdmissionController controller = new RunAdmissionController(props)) {
            UUID convA = UUID.randomUUID();
            UUID convB = UUID.randomUUID();

            RunAdmissionController.AdmissionTicket ticketA = controller.requestAdmission("t1", 1L, convA, "run-1");
            RunAdmissionController.AdmissionTicket ticketB = controller.requestAdmission("t1", 1L, convB, "run-2");

            assertThat(ticketA.decision()).isEqualTo(AdmissionDecision.ADMITTED);
            assertThat(ticketB.decision()).isEqualTo(AdmissionDecision.ADMITTED);
            assertThat(controller.getActiveRunCount()).isEqualTo(2);
            assertThat(controller.getUserActiveRunCount("t1", 1L)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("会话串行化测试：同一会话后续 Run 排队等待前序 Run 终态释放后方可准入")
    void secondRunInOneConversationWaitsForFirstTerminalState() throws Exception {
        RuntimeCapacityProperties props = RuntimeCapacityProperties.builder()
                .maxActiveRunsPerUser(2)
                .build();
        try (RunAdmissionController controller = new RunAdmissionController(props)) {
            UUID convA = UUID.randomUUID();

            // 第一个 Run 即时准入
            RunAdmissionController.AdmissionTicket ticket1 = controller.requestAdmission("t1", 1L, convA, "run-1");
            assertThat(ticket1.decision()).isEqualTo(AdmissionDecision.ADMITTED);
            assertThat(ticket1.onAdmittedFuture().isDone()).isTrue();

            // 第二个 Run 进入同会话串行化排队
            RunAdmissionController.AdmissionTicket ticket2 = controller.requestAdmission("t1", 1L, convA, "run-2");
            assertThat(ticket2.decision()).isEqualTo(AdmissionDecision.QUEUED_CONVERSATION);
            assertThat(ticket2.onAdmittedFuture().isDone()).isFalse();
            assertThat(controller.getQueuedRunCount()).isEqualTo(1);

            // 前序 Run 完成释放
            controller.onRunCompleted("run-1");

            // 验证第二个 Run 成功转为 ADMITTED
            AdmissionDecision decision = ticket2.onAdmittedFuture().get(2, TimeUnit.SECONDS);
            assertThat(decision).isEqualTo(AdmissionDecision.ADMITTED);
            assertThat(controller.getQueuedRunCount()).isEqualTo(0);
            assertThat(controller.getActiveRunCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("用户限额排队测试：单用户超出 maxActiveRunsPerUser 进入排队，释放后自动唤醒")
    void userExceedingMaxActiveRunsIsQueuedAndAdmittedOnRelease() throws Exception {
        RuntimeCapacityProperties props = RuntimeCapacityProperties.builder()
                .maxActiveRunsPerUser(2)
                .build();
        try (RunAdmissionController controller = new RunAdmissionController(props)) {
            UUID convA = UUID.randomUUID();
            UUID convB = UUID.randomUUID();
            UUID convC = UUID.randomUUID();

            controller.requestAdmission("t1", 1L, convA, "run-1");
            controller.requestAdmission("t1", 1L, convB, "run-2");

            // 第 3 个不同会话，因用户并发限额进入排队
            RunAdmissionController.AdmissionTicket ticket3 = controller.requestAdmission("t1", 1L, convC, "run-3");
            assertThat(ticket3.decision()).isEqualTo(AdmissionDecision.QUEUED_USER_LIMIT);
            assertThat(ticket3.onAdmittedFuture().isDone()).isFalse();

            // 释放 run-1
            controller.onRunCompleted("run-1");

            AdmissionDecision decision = ticket3.onAdmittedFuture().get(2, TimeUnit.SECONDS);
            assertThat(decision).isEqualTo(AdmissionDecision.ADMITTED);
            assertThat(controller.getUserActiveRunCount("t1", 1L)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("排队取消测试：取消排队中的 Run 会将其移出队列并释放准入计数")
    void cancellingQueuedRunRemovesItAndReleasesAdmissionCounts() {
        RuntimeCapacityProperties props = RuntimeCapacityProperties.builder()
                .maxActiveRunsPerUser(1)
                .build();
        try (RunAdmissionController controller = new RunAdmissionController(props)) {
            UUID convA = UUID.randomUUID();
            controller.requestAdmission("t1", 1L, convA, "run-1");

            RunAdmissionController.AdmissionTicket ticket2 = controller.requestAdmission("t1", 1L, convA, "run-2");
            assertThat(controller.getQueuedRunCount()).isEqualTo(1);

            boolean cancelled = controller.cancelQueuedRun("run-2");
            assertThat(cancelled).isTrue();
            assertThat(controller.getQueuedRunCount()).isEqualTo(0);
            assertThat(ticket2.onAdmittedFuture().isCompletedExceptionally()).isTrue();
        }
    }

    @Test
    @DisplayName("排队超时保护测试：排队等待超过 queueTimeout 自动触发超时失败终态")
    void queueTimeoutReachesTerminalState() {
        RuntimeCapacityProperties props = RuntimeCapacityProperties.builder()
                .maxActiveRunsPerUser(1)
                .queueTimeout(Duration.ofMillis(50)) // 50ms 超时
                .build();
        try (RunAdmissionController controller = new RunAdmissionController(props)) {
            UUID convA = UUID.randomUUID();
            controller.requestAdmission("t1", 1L, convA, "run-1");

            RunAdmissionController.AdmissionTicket ticket2 = controller.requestAdmission("t1", 1L, convA, "run-2");
            assertThat(ticket2.decision()).isEqualTo(AdmissionDecision.QUEUED_CONVERSATION);

            // 等待超过 50ms，验证 Future 抛出超时异常
            assertThatThrownBy(() -> ticket2.onAdmittedFuture().get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TimeoutException.class);

            assertThat(controller.getQueuedRunCount()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("全局容量保护测试：超出系统全局排队上限 maxQueuedRuns 直接拒绝准入")
    void systemCapacityExhaustionRejectsNewRuns() {
        RuntimeCapacityProperties props = RuntimeCapacityProperties.builder()
                .maxActiveRuns(1)
                .maxQueuedRuns(2)
                .build();
        try (RunAdmissionController controller = new RunAdmissionController(props)) {
            // 活跃 1 个
            controller.requestAdmission("t1", 1L, UUID.randomUUID(), "run-1");

            // 排队 2 个
            controller.requestAdmission("t1", 2L, UUID.randomUUID(), "run-2");
            controller.requestAdmission("t1", 3L, UUID.randomUUID(), "run-3");
            assertThat(controller.getQueuedRunCount()).isEqualTo(2);

            // 第 4 个因系统排队满载被拒绝
            RunAdmissionController.AdmissionTicket ticket4 = controller.requestAdmission("t1", 4L, UUID.randomUUID(), "run-4");
            assertThat(ticket4.decision()).isEqualTo(AdmissionDecision.REJECTED_SYSTEM_CAPACITY);
            assertThat(ticket4.onAdmittedFuture().isCompletedExceptionally()).isTrue();
        }
    }
}
