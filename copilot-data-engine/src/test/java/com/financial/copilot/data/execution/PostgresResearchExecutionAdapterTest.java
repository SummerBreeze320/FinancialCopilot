package com.financial.copilot.data.execution;

import com.financial.copilot.data.execution.adapter.PostgresResearchExecutionAdapter;
import com.financial.copilot.data.execution.mapper.NodeAttemptMapper;
import com.financial.copilot.data.execution.mapper.ResearchRunEventMapper;
import com.financial.copilot.data.execution.mapper.ResearchRunStateMapper;
import com.financial.copilot.data.execution.po.NodeAttemptPO;
import com.financial.copilot.data.execution.po.ResearchRunEventPO;
import com.financial.copilot.data.execution.po.ResearchRunStatePO;
import com.financial.copilot.domain.platform.execution.entity.NodeAttempt;
import com.financial.copilot.domain.platform.execution.entity.ResearchRunEvent;
import com.financial.copilot.domain.platform.execution.entity.ResearchRunState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <h1>PostgresResearchExecutionAdapter 单元测试</h1>
 * <p>
 * 验证分布式执行状态落盘、行级锁抢占、租约过期重认领、执行完成隔离及单调递增事件重放。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class PostgresResearchExecutionAdapterTest {

    @Mock
    private ResearchRunStateMapper runStateMapper;

    @Mock
    private NodeAttemptMapper nodeAttemptMapper;

    @Mock
    private ResearchRunEventMapper eventMapper;

    private PostgresResearchExecutionAdapter adapter;

    private final Long userId = 1001L;
    private final UUID runId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final String nodeId = "node_analyzer";

    @BeforeEach
    void setUp() {
        adapter = new PostgresResearchExecutionAdapter(runStateMapper, nodeAttemptMapper, eventMapper);
    }

    @Test
    @DisplayName("saveRunState 映射实体并持久化初始执行状态")
    void saveRunState_shouldUpsertRunState() {
        Instant now = Instant.now();
        ResearchRunState state = new ResearchRunState(runId, userId, conversationId, "RUNNING", "{\"nodes\":[]}", 0L, now, now);

        adapter.saveRunState(state);

        ArgumentCaptor<ResearchRunStatePO> captor = ArgumentCaptor.forClass(ResearchRunStatePO.class);
        verify(runStateMapper).upsertState(captor.capture());
        ResearchRunStatePO captured = captor.getValue();

        assertThat(captured.getRunId()).isEqualTo(runId);
        assertThat(captured.getUserId()).isEqualTo(userId);
        assertThat(captured.getConversationId()).isEqualTo(conversationId);
        assertThat(captured.getStatus()).isEqualTo("RUNNING");
        assertThat(captured.getVersion()).isEqualTo(0L);
    }

    @Test
    @DisplayName("updateRunState 乐观锁版本匹配时成功更新，冲突时返回 false")
    void updateRunState_shouldUseOptimisticLocking() {
        Instant now = Instant.now();
        when(runStateMapper.updateStateOptimistic(eq(userId), eq(runId), eq("SUCCEEDED"), anyString(), eq(0L), any()))
                .thenReturn(1);
        when(runStateMapper.updateStateOptimistic(eq(userId), eq(runId), eq("SUCCEEDED"), anyString(), eq(999L), any()))
                .thenReturn(0);

        boolean success = adapter.updateRunState(userId, runId, "SUCCEEDED", "{}", 0L, now);
        boolean conflicted = adapter.updateRunState(userId, runId, "SUCCEEDED", "{}", 999L, now);

        assertThat(success).isTrue();
        assertThat(conflicted).isFalse();
    }

    @Test
    @DisplayName("cancelRun 将未完成的 Run 标记为 CANCELLED")
    void cancelRun_shouldCancelActiveRun() {
        Instant now = Instant.now();
        when(runStateMapper.cancelRun(eq(userId), eq(runId), any())).thenReturn(1);

        boolean cancelled = adapter.cancelRun(userId, runId, "User requested cancellation", now);

        assertThat(cancelled).isTrue();
        verify(runStateMapper).cancelRun(eq(userId), eq(runId), any());
    }

    @Test
    @DisplayName("worker A 抢占节点尝试 1，租约过期后由 worker B 成功接管并完成")
    void workerLeaseTakeover_workerBReclaimsExpiredAttemptAndWorkerACannotComplete() {
        Instant now = Instant.now();
        Instant leaseWorkerA = now.plusSeconds(300);
        String workerA = "worker-host-a";
        String workerB = "worker-host-b";

        // 1. Worker A 抢占就绪节点
        NodeAttemptPO readyPO = NodeAttemptPO.builder()
                .runId(runId)
                .nodeId(nodeId)
                .attempt(1)
                .status("READY")
                .workerId(null)
                .leaseUntil(null)
                .artifact("{}")
                .build();

        when(nodeAttemptMapper.selectReadyOrExpiredForUpdate(any(), eq(10)))
                .thenReturn(List.of(readyPO));
        when(nodeAttemptMapper.claimAttempt(eq(runId), eq(nodeId), eq(1), eq(workerA), any()))
                .thenReturn(1);

        List<NodeAttempt> claimedByA = adapter.claimReadyNodes(workerA, 10, leaseWorkerA, now);
        assertThat(claimedByA).hasSize(1);
        assertThat(claimedByA.get(0).workerId()).isEqualTo(workerA);
        assertThat(claimedByA.get(0).status()).isEqualTo("RUNNING");

        // 2. Worker A 租约过期，Worker B 发现过期节点并抢占
        Instant later = now.plusSeconds(301);
        Instant leaseWorkerB = later.plusSeconds(300);
        NodeAttemptPO expiredPO = NodeAttemptPO.builder()
                .runId(runId)
                .nodeId(nodeId)
                .attempt(1)
                .status("RUNNING")
                .workerId(workerA)
                .leaseUntil(LocalDateTime.ofInstant(leaseWorkerA, ZoneOffset.UTC))
                .artifact("{}")
                .build();

        when(nodeAttemptMapper.selectReadyOrExpiredForUpdate(any(), eq(10)))
                .thenReturn(List.of(expiredPO));
        when(nodeAttemptMapper.claimAttempt(eq(runId), eq(nodeId), eq(1), eq(workerB), any()))
                .thenReturn(1);

        List<NodeAttempt> claimedByB = adapter.claimReadyNodes(workerB, 10, leaseWorkerB, later);
        assertThat(claimedByB).hasSize(1);
        assertThat(claimedByB.get(0).workerId()).isEqualTo(workerB);

        // 3. Worker A 尝试提交已失效的执行产物，由于 workerId 校验不通过而失败 (更新 0 行)
        when(nodeAttemptMapper.completeAttempt(eq(runId), eq(nodeId), eq(1), eq(workerA), eq("SUCCEEDED"), anyString()))
                .thenReturn(0);
        boolean workerASubmit = adapter.completeAttempt(runId, nodeId, 1, workerA, "SUCCEEDED", "{\"report\":\"A\"}");
        assertThat(workerASubmit).isFalse();

        // 4. 仅当前持有租约的 Worker B 能够成功提交
        when(nodeAttemptMapper.completeAttempt(eq(runId), eq(nodeId), eq(1), eq(workerB), eq("SUCCEEDED"), anyString()))
                .thenReturn(1);
        boolean workerBSubmit = adapter.completeAttempt(runId, nodeId, 1, workerB, "SUCCEEDED", "{\"report\":\"B\"}");
        assertThat(workerBSubmit).isTrue();
    }

    @Test
    @DisplayName("appendEvent 生成单调递增序号，getEventsAfter 支持游标断点续传")
    void eventsMonotonicityAndReplay() {
        Instant now = Instant.now();
        when(eventMapper.nextSequence(runId)).thenReturn(1L, 2L, 3L);
        when(eventMapper.insertEvent(any())).thenReturn(1);

        ResearchRunEvent e1 = adapter.appendEvent(runId, userId, "run_started", "{\"phase\":\"started\"}", now);
        ResearchRunEvent e2 = adapter.appendEvent(runId, userId, "node_started", "{\"nodeId\":\"node1\"}", now);
        ResearchRunEvent e3 = adapter.appendEvent(runId, userId, "run_completed", "{\"status\":\"SUCCEEDED\"}", now);

        assertThat(e1.sequenceNo()).isEqualTo(1L);
        assertThat(e2.sequenceNo()).isEqualTo(2L);
        assertThat(e3.sequenceNo()).isEqualTo(3L);

        // 模拟客户端通过 Last-Event-ID: 1 断点续传，只重放序号大于 1 的事件
        ResearchRunEventPO po2 = ResearchRunEventPO.builder().runId(runId).userId(userId).sequenceNo(2L).eventType("node_started").payload("{}").createdAt(LocalDateTime.now()).build();
        ResearchRunEventPO po3 = ResearchRunEventPO.builder().runId(runId).userId(userId).sequenceNo(3L).eventType("run_completed").payload("{}").createdAt(LocalDateTime.now()).build();
        when(eventMapper.listAfterSequence(userId, runId, 1L)).thenReturn(List.of(po2, po3));

        List<ResearchRunEvent> replayed = adapter.getEventsAfter(userId, runId, 1L);
        assertThat(replayed).hasSize(2);
        assertThat(replayed).extracting(ResearchRunEvent::sequenceNo).containsExactly(2L, 3L);
    }
}
