package com.financial.copilot.domain.platform.execution.port;

import com.financial.copilot.domain.platform.execution.entity.NodeAttempt;
import com.financial.copilot.domain.platform.execution.entity.ResearchRunEvent;
import com.financial.copilot.domain.platform.execution.entity.ResearchRunState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <h1>集群化运行执行与事件持久化端口 (Research Execution Port)</h1>
 * <p>
 * 定义多实例部署下运行状态同步、节点执行租约抢占 (Lease Claiming)、租约续期、产物提交及有序事件重放核心契约。
 * </p>
 *
 * @author FinancialCopilot
 */
public interface ResearchExecutionPort {

    /**
     * 保存或初始化运行状态
     *
     * @param state 运行状态实体
     */
    void saveRunState(ResearchRunState state);

    /**
     * 乐观锁更新运行状态
     *
     * @param userId          用户 ID
     * @param runId           运行 ID
     * @param status          目标状态
     * @param executionSnapshot 最新执行与证据链快照 JSON
     * @param expectedVersion   期望的乐观锁版本
     * @param now               更新时间
     * @return 更新是否成功（版本匹配时为 true）
     */
    boolean updateRunState(Long userId, UUID runId, String status, String executionSnapshot, Long expectedVersion, Instant now);

    /**
     * 查询指定用户下的运行状态
     *
     * @param userId 用户 ID
     * @param runId  运行 ID
     * @return 运行状态（若不存在或用户不匹配返回 empty）
     */
    Optional<ResearchRunState> findRunState(Long userId, UUID runId);

    /**
     * 标记运行为已取消
     *
     * @param userId 用户 ID
     * @param runId  运行 ID
     * @param reason 取消原因
     * @param now    取消时间
     * @return 是否成功更新为 CANCELLED 状态
     */
    boolean cancelRun(Long userId, UUID runId, String reason, Instant now);

    /**
     * 注册或初始化就绪节点尝试
     *
     * @param attempt 节点尝试
     */
    void createNodeAttempt(NodeAttempt attempt);

    /**
     * 抢占就绪或租约已过期的节点（FOR UPDATE SKIP LOCKED）
     *
     * @param workerId   抢占者工作节点标识
     * @param limit      抢占数量上限
     * @param leaseUntil 租约有效期
     * @param now        当前时间戳
     * @return 抢占成功的节点尝试列表
     */
    List<NodeAttempt> claimReadyNodes(String workerId, int limit, Instant leaseUntil, Instant now);

    /**
     * 续订工作节点的节点执行租约
     *
     * @param runId         运行 ID
     * @param nodeId        节点 ID
     * @param attempt       尝试序号
     * @param workerId      工作节点标识
     * @param newLeaseUntil 新的租约截止时间
     * @return 是否续租成功（必须与当前持有者匹配且状态为 RUNNING）
     */
    boolean renewLease(UUID runId, String nodeId, int attempt, String workerId, Instant newLeaseUntil);

    /**
     * 条件提交节点尝试的最终状态与产物
     *
     * @param runId        运行 ID
     * @param nodeId       节点 ID
     * @param attempt      尝试序号
     * @param workerId     工作节点标识
     * @param finalStatus  最终状态（SUCCEEDED 或 FAILED）
     * @param artifactJson 产物 JSON
     * @return 是否提交成功
     */
    boolean completeAttempt(UUID runId, String nodeId, int attempt, String workerId, String finalStatus, String artifactJson);

    /**
     * 释放所有过期的租约回滚至 READY 状态供其他 Worker 抢占
     *
     * @param now 当前时间戳
     * @return 重置回 READY 的尝试数量
     */
    int releaseExpiredLeases(Instant now);

    /**
     * 查询指定运行下的所有节点尝试
     *
     * @param runId 运行 ID
     * @return 节点尝试列表
     */
    List<NodeAttempt> findNodeAttempts(UUID runId);

    /**
     * 查询指定节点与尝试序号的尝试记录
     *
     * @param runId   运行 ID
     * @param nodeId  节点 ID
     * @param attempt 尝试序号
     * @return 尝试记录
     */
    Optional<NodeAttempt> findNodeAttempt(UUID runId, String nodeId, int attempt);

    /**
     * 追加事件记录并分配单调递增序号
     *
     * @param runId       运行 ID
     * @param userId      用户 ID
     * @param eventType   事件类型
     * @param payloadJson 事件载荷 JSON
     * @param now         发生时间戳
     * @return 带有已分配 sequenceNo 的持久化事件实体
     */
    ResearchRunEvent appendEvent(UUID runId, Long userId, String eventType, String payloadJson, Instant now);

    /**
     * 查询指定运行在给定 sequenceNo 之后的重放事件列表
     *
     * @param userId          用户 ID
     * @param runId           运行 ID
     * @param afterSequenceNo 起始序号（不含）
     * @return 有序事件列表
     */
    List<ResearchRunEvent> getEventsAfter(Long userId, UUID runId, long afterSequenceNo);
}
