package com.financial.copilot.data.execution.adapter;

import com.financial.copilot.data.execution.mapper.NodeAttemptMapper;
import com.financial.copilot.data.execution.mapper.ResearchRunEventMapper;
import com.financial.copilot.data.execution.mapper.ResearchRunStateMapper;
import com.financial.copilot.data.execution.po.NodeAttemptPO;
import com.financial.copilot.data.execution.po.ResearchRunEventPO;
import com.financial.copilot.data.execution.po.ResearchRunStatePO;
import com.financial.copilot.domain.platform.execution.entity.NodeAttempt;
import com.financial.copilot.domain.platform.execution.entity.ResearchRunEvent;
import com.financial.copilot.domain.platform.execution.entity.ResearchRunState;
import com.financial.copilot.domain.platform.execution.port.ResearchExecutionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * <h1>基于 PostgreSQL 的集群化图执行与分布式租约持久化适配器</h1>
 * <p>
 * 实现领域层 {@link ResearchExecutionPort} SPI 端口，
 * 支持基于行级排他锁 (FOR UPDATE SKIP LOCKED) 的工作抢占、租约心跳续期、运行状态版本流转与 SSE 有序重放。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostgresResearchExecutionAdapter implements ResearchExecutionPort {

    private final ResearchRunStateMapper runStateMapper;
    private final NodeAttemptMapper nodeAttemptMapper;
    private final ResearchRunEventMapper eventMapper;

    @Override
    @Transactional("jdbcTransactionManager")
    public void saveRunState(ResearchRunState state) {
        Objects.requireNonNull(state, "state cannot be null");
        ResearchRunStatePO po = ResearchRunStatePO.builder()
                .runId(state.runId())
                .userId(state.userId())
                .conversationId(state.conversationId())
                .status(state.status())
                .graphSnapshot(state.graphSnapshot())
                .version(state.version())
                .createdAt(toLocalDateTime(state.createdAt()))
                .updatedAt(toLocalDateTime(state.updatedAt()))
                .build();
        runStateMapper.upsertState(po);
    }

    @Override
    @Transactional("jdbcTransactionManager")
    public boolean updateRunState(Long userId, UUID runId, String status, String executionSnapshot, Long expectedVersion, Instant now) {
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        String snapshot = executionSnapshot != null ? executionSnapshot : "{}";
        int rows = runStateMapper.updateStateOptimistic(userId, runId, status, snapshot, expectedVersion, toLocalDateTime(now));
        return rows > 0;
    }

    @Override
    public Optional<ResearchRunState> findRunState(Long userId, UUID runId) {
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(runId, "runId cannot be null");
        ResearchRunStatePO po = runStateMapper.findByUserAndRun(userId, runId);
        if (po == null) {
            return Optional.empty();
        }
        return Optional.of(toDomainRunState(po));
    }

    @Override
    @Transactional("jdbcTransactionManager")
    public boolean cancelRun(Long userId, UUID runId, String reason, Instant now) {
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(runId, "runId cannot be null");
        int rows = runStateMapper.cancelRun(userId, runId, toLocalDateTime(now));
        return rows > 0;
    }

    @Override
    @Transactional("jdbcTransactionManager")
    public void createNodeAttempt(NodeAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt cannot be null");
        NodeAttemptPO po = NodeAttemptPO.builder()
                .runId(attempt.runId())
                .nodeId(attempt.nodeId())
                .attempt(attempt.attempt())
                .status(attempt.status())
                .workerId(attempt.workerId())
                .leaseUntil(toLocalDateTime(attempt.leaseUntil()))
                .artifact(attempt.artifact() != null ? attempt.artifact() : "{}")
                .build();
        nodeAttemptMapper.insertAttempt(po);
    }

    @Override
    @Transactional("jdbcTransactionManager")
    public List<NodeAttempt> claimReadyNodes(String workerId, int limit, Instant leaseUntil, Instant now) {
        Objects.requireNonNull(workerId, "workerId cannot be null");
        LocalDateTime nowLdt = toLocalDateTime(now);
        LocalDateTime leaseUntilLdt = toLocalDateTime(leaseUntil);

        List<NodeAttemptPO> locked = nodeAttemptMapper.selectReadyOrExpiredForUpdate(nowLdt, limit);
        if (locked == null || locked.isEmpty()) {
            return List.of();
        }

        List<NodeAttempt> claimed = new ArrayList<>(locked.size());
        for (NodeAttemptPO po : locked) {
            nodeAttemptMapper.claimAttempt(po.getRunId(), po.getNodeId(), po.getAttempt(), workerId, leaseUntilLdt);
            claimed.add(new NodeAttempt(
                    po.getRunId(),
                    po.getNodeId(),
                    po.getAttempt(),
                    "RUNNING",
                    workerId,
                    leaseUntil,
                    po.getArtifact()
            ));
        }
        return claimed;
    }

    @Override
    @Transactional("jdbcTransactionManager")
    public boolean renewLease(UUID runId, String nodeId, int attempt, String workerId, Instant newLeaseUntil) {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(nodeId, "nodeId cannot be null");
        Objects.requireNonNull(workerId, "workerId cannot be null");
        Objects.requireNonNull(newLeaseUntil, "newLeaseUntil cannot be null");
        int rows = nodeAttemptMapper.renewLease(runId, nodeId, attempt, workerId, toLocalDateTime(newLeaseUntil));
        return rows > 0;
    }

    @Override
    @Transactional("jdbcTransactionManager")
    public boolean completeAttempt(UUID runId, String nodeId, int attempt, String workerId, String finalStatus, String artifactJson) {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(nodeId, "nodeId cannot be null");
        Objects.requireNonNull(workerId, "workerId cannot be null");
        Objects.requireNonNull(finalStatus, "finalStatus cannot be null");
        String artifact = artifactJson != null ? artifactJson : "{}";
        int rows = nodeAttemptMapper.completeAttempt(runId, nodeId, attempt, workerId, finalStatus, artifact);
        return rows > 0;
    }

    @Override
    @Transactional("jdbcTransactionManager")
    public int releaseExpiredLeases(Instant now) {
        return nodeAttemptMapper.releaseExpiredLeases(toLocalDateTime(now));
    }

    @Override
    public List<NodeAttempt> findNodeAttempts(UUID runId) {
        Objects.requireNonNull(runId, "runId cannot be null");
        List<NodeAttemptPO> pos = nodeAttemptMapper.findByRun(runId);
        if (pos == null || pos.isEmpty()) {
            return List.of();
        }
        return pos.stream().map(this::toDomainNodeAttempt).toList();
    }

    @Override
    public Optional<NodeAttempt> findNodeAttempt(UUID runId, String nodeId, int attempt) {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(nodeId, "nodeId cannot be null");
        NodeAttemptPO po = nodeAttemptMapper.findSpecificAttempt(runId, nodeId, attempt);
        if (po == null) {
            return Optional.empty();
        }
        return Optional.of(toDomainNodeAttempt(po));
    }

    @Override
    @Transactional("jdbcTransactionManager")
    public synchronized ResearchRunEvent appendEvent(UUID runId, Long userId, String eventType, String payloadJson, Instant now) {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(eventType, "eventType cannot be null");
        String payload = payloadJson != null ? payloadJson : "{}";
        LocalDateTime nowLdt = toLocalDateTime(now != null ? now : Instant.now());

        Long seq = eventMapper.nextSequence(runId);
        ResearchRunEventPO po = ResearchRunEventPO.builder()
                .runId(runId)
                .userId(userId)
                .sequenceNo(seq)
                .eventType(eventType)
                .payload(payload)
                .createdAt(nowLdt)
                .build();
        eventMapper.insertEvent(po);
        return new ResearchRunEvent(runId, userId, seq, eventType, payload, toInstant(nowLdt));
    }

    @Override
    public List<ResearchRunEvent> getEventsAfter(Long userId, UUID runId, long afterSequenceNo) {
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(runId, "runId cannot be null");
        List<ResearchRunEventPO> pos = eventMapper.listAfterSequence(userId, runId, afterSequenceNo);
        if (pos == null || pos.isEmpty()) {
            return List.of();
        }
        return pos.stream().map(this::toDomainEvent).toList();
    }

    private ResearchRunState toDomainRunState(ResearchRunStatePO po) {
        return new ResearchRunState(
                po.getRunId(),
                po.getUserId(),
                po.getConversationId(),
                po.getStatus(),
                po.getGraphSnapshot(),
                po.getVersion(),
                toInstant(po.getCreatedAt()),
                toInstant(po.getUpdatedAt())
        );
    }

    private NodeAttempt toDomainNodeAttempt(NodeAttemptPO po) {
        return new NodeAttempt(
                po.getRunId(),
                po.getNodeId(),
                po.getAttempt(),
                po.getStatus(),
                po.getWorkerId(),
                toInstant(po.getLeaseUntil()),
                po.getArtifact()
        );
    }

    private ResearchRunEvent toDomainEvent(ResearchRunEventPO po) {
        return new ResearchRunEvent(
                po.getRunId(),
                po.getUserId(),
                po.getSequenceNo(),
                po.getEventType(),
                po.getPayload(),
                toInstant(po.getCreatedAt())
        );
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }
}
