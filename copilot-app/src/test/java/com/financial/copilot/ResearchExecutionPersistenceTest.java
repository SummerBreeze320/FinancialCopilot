package com.financial.copilot;

import com.financial.copilot.domain.platform.execution.entity.NodeAttempt;
import com.financial.copilot.domain.platform.execution.entity.ResearchRunEvent;
import com.financial.copilot.domain.platform.execution.entity.ResearchRunState;
import com.financial.copilot.domain.platform.execution.port.ResearchExecutionPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>集群化运行执行持久化集成测试 (PostgreSQL)</h1>
 * <p>
 * 仅在配置 {@code -Dcopilot.integration=true} 时执行真实数据库联调，
 * 验证分布式租约争夺、工作窃取、乐观锁及 SSE 事件续传在真实 PostgreSQL 事务下的原子性与隔离性。
 * </p>
 */
@EnabledIfSystemProperty(named = "copilot.integration", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResearchExecutionPersistenceTest {

    @Autowired
    private ResearchExecutionPort port;

    @Autowired
    private JdbcTemplate jdbc;

    private static final long OWNER_USER_ID = 8_000_000_001L;
    private static final long STRANGER_USER_ID = 8_000_000_002L;

    @Test
    @DisplayName("集群执行状态保存、乐观锁更新与租约两阶段接管完整流程")
    void testClusteredExecutionLifecycleAndTakeover() {
        UUID runId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Instant now = Instant.now();

        try {
            // 1. 初始化运行状态
            ResearchRunState initialState = new ResearchRunState(
                    runId, OWNER_USER_ID, conversationId, "RUNNING", "{\"nodes\":[\"step1\"]}", 0L, now, now);
            port.saveRunState(initialState);

            Optional<ResearchRunState> loaded = port.findRunState(OWNER_USER_ID, runId);
            assertThat(loaded).isPresent();
            assertThat(loaded.get().status()).isEqualTo("RUNNING");
            assertThat(loaded.get().version()).isEqualTo(0L);

            // 越权用户无法读取他人 Run 状态
            assertThat(port.findRunState(STRANGER_USER_ID, runId)).isEmpty();

            // 2. 乐观锁版本更新
            boolean updated = port.updateRunState(OWNER_USER_ID, runId, "RUNNING", "{\"nodes\":[\"step1\",\"step2\"]}", 0L, now);
            assertThat(updated).isTrue();

            boolean staleUpdate = port.updateRunState(OWNER_USER_ID, runId, "SUCCEEDED", "{}", 0L, now);
            assertThat(staleUpdate).isFalse();

            // 3. 注册就绪节点尝试
            String nodeId = "deep_fund_analyzer";
            port.createNodeAttempt(new NodeAttempt(runId, nodeId, 1, "READY", null, null, null));

            // 4. Worker A 抢占节点租约 (5 秒有效)
            String workerA = "worker-host-a";
            Instant leaseUntilA = now.plusSeconds(5);
            List<NodeAttempt> claimedA = port.claimReadyNodes(workerA, 10, leaseUntilA, now);
            assertThat(claimedA).hasSize(1);
            assertThat(claimedA.get(0).workerId()).isEqualTo(workerA);

            // 5. 模拟 Worker A 宕机未续租，租约到期后由 Worker B 接管
            String workerB = "worker-host-b";
            Instant expiredTime = now.plusSeconds(6);
            Instant leaseUntilB = expiredTime.plusSeconds(10);
            List<NodeAttempt> claimedB = port.claimReadyNodes(workerB, 10, leaseUntilB, expiredTime);
            assertThat(claimedB).hasSize(1);
            assertThat(claimedB.get(0).workerId()).isEqualTo(workerB);

            // 6. Worker A 尝试提交产物必须被拒绝 (条件更新行数为 0)
            boolean workerACompleted = port.completeAttempt(runId, nodeId, 1, workerA, "SUCCEEDED", "{\"data\":\"A\"}");
            assertThat(workerACompleted).isFalse();

            // 7. Worker B 成功提交最终产物
            boolean workerBCompleted = port.completeAttempt(runId, nodeId, 1, workerB, "SUCCEEDED", "{\"data\":\"B\"}");
            assertThat(workerBCompleted).isTrue();

            // 8. 流式事件单调追加与 SSE Last-Event-ID 重放
            ResearchRunEvent event1 = port.appendEvent(runId, OWNER_USER_ID, "run_started", "{\"step\":1}", now);
            ResearchRunEvent event2 = port.appendEvent(runId, OWNER_USER_ID, "node_started", "{\"step\":2}", now);
            ResearchRunEvent event3 = port.appendEvent(runId, OWNER_USER_ID, "run_completed", "{\"step\":3}", now);

            assertThat(event1.sequenceNo()).isEqualTo(1L);
            assertThat(event2.sequenceNo()).isEqualTo(2L);
            assertThat(event3.sequenceNo()).isEqualTo(3L);

            // 模拟客户端重连 Last-Event-ID: 1
            List<ResearchRunEvent> replayed = port.getEventsAfter(OWNER_USER_ID, runId, 1L);
            assertThat(replayed).extracting(ResearchRunEvent::sequenceNo).containsExactly(2L, 3L);

            // 越权用户无法读取他人事件流
            assertThat(port.getEventsAfter(STRANGER_USER_ID, runId, 0L)).isEmpty();

        } finally {
            cleanupRun(runId);
        }
    }

    private void cleanupRun(UUID runId) {
        try {
            jdbc.update("DELETE FROM research_run_event WHERE run_id = ?", runId);
            jdbc.update("DELETE FROM research_node_attempt WHERE run_id = ?", runId);
            jdbc.update("DELETE FROM research_run_state WHERE run_id = ?", runId);
        } catch (Exception ignored) {}
    }
}
