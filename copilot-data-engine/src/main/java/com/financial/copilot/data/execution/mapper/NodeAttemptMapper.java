package com.financial.copilot.data.execution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.execution.po.NodeAttemptPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * <h1>节点执行尝试与分布式租约数据访问映射器 (MyBatis-Plus Mapper)</h1>
 * <p>
 * 提供针对 {@code research_node_attempt} 表的行级排他锁抢占 (FOR UPDATE SKIP LOCKED)、租约续期、产物提交与超时释放。
 * </p>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface NodeAttemptMapper extends BaseMapper<NodeAttemptPO> {

    /**
     * 插入新的节点执行尝试（若已存在则忽略）
     *
     * @param po 节点尝试持久化实体
     * @return 影响行数
     */
    @Insert("INSERT INTO research_node_attempt (run_id, node_id, attempt, status, worker_id, lease_until, artifact) " +
            "VALUES (#{runId}, #{nodeId}, #{attempt}, #{status}, #{workerId}, #{leaseUntil}, CAST(#{artifact} AS jsonb)) " +
            "ON CONFLICT (run_id, node_id, attempt) DO NOTHING")
    int insertAttempt(NodeAttemptPO po);

    /**
     * 以行锁及跳过锁定模式查询处于 READY 状态或租约已过期的节点尝试
     *
     * @param now   当前时间戳
     * @param limit 最大拉取条数
     * @return 符合抢占条件的节点列表（行已被加排他锁）
     */
    @Select("SELECT * FROM research_node_attempt " +
            "WHERE status = 'READY' OR (status = 'RUNNING' AND lease_until < #{now}) " +
            "ORDER BY run_id, node_id, attempt LIMIT #{limit} " +
            "FOR UPDATE SKIP LOCKED")
    List<NodeAttemptPO> selectReadyOrExpiredForUpdate(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 认领节点执行尝试（更新状态为 RUNNING 并设置 worker_id 与租约截止期）
     *
     * @param runId      运行批次 Run ID
     * @param nodeId     节点 ID
     * @param attempt    尝试序号
     * @param workerId   工作节点标识
     * @param leaseUntil 租约有效期
     * @return 影响行数
     */
    @Update("UPDATE research_node_attempt SET status = 'RUNNING', worker_id = #{workerId}, lease_until = #{leaseUntil} " +
            "WHERE run_id = #{runId} AND node_id = #{nodeId} AND attempt = #{attempt}")
    int claimAttempt(@Param("runId") UUID runId, @Param("nodeId") String nodeId,
                     @Param("attempt") Integer attempt, @Param("workerId") String workerId,
                     @Param("leaseUntil") LocalDateTime leaseUntil);

    /**
     * 续订节点租约（必须匹配持有者 workerId 且当前为 RUNNING 状态）
     *
     * @param runId         运行批次 Run ID
     * @param nodeId        节点 ID
     * @param attempt       尝试序号
     * @param workerId      工作节点标识
     * @param newLeaseUntil 新的租约截止时间
     * @return 影响行数
     */
    @Update("UPDATE research_node_attempt SET lease_until = #{newLeaseUntil} " +
            "WHERE run_id = #{runId} AND node_id = #{nodeId} AND attempt = #{attempt} " +
            "AND worker_id = #{workerId} AND status = 'RUNNING'")
    int renewLease(@Param("runId") UUID runId, @Param("nodeId") String nodeId,
                   @Param("attempt") Integer attempt, @Param("workerId") String workerId,
                   @Param("newLeaseUntil") LocalDateTime newLeaseUntil);

    /**
     * 条件提交节点最终状态与产物（必须匹配持有者 workerId 且当前为 RUNNING 状态）
     *
     * @param runId       运行批次 Run ID
     * @param nodeId      节点 ID
     * @param attempt     尝试序号
     * @param workerId    工作节点标识
     * @param finalStatus 最终状态（SUCCEEDED 或 FAILED）
     * @param artifact    产物 JSONB 文本
     * @return 影响行数
     */
    @Update("UPDATE research_node_attempt SET status = #{finalStatus}, artifact = CAST(#{artifact} AS jsonb) " +
            "WHERE run_id = #{runId} AND node_id = #{nodeId} AND attempt = #{attempt} " +
            "AND worker_id = #{workerId} AND status = 'RUNNING'")
    int completeAttempt(@Param("runId") UUID runId, @Param("nodeId") String nodeId,
                        @Param("attempt") Integer attempt, @Param("workerId") String workerId,
                        @Param("finalStatus") String finalStatus, @Param("artifact") String artifact);

    /**
     * 释放所有过期的 RUNNING 租约并回滚至 READY 状态供其他 Worker 抢占
     *
     * @param now 当前时间戳
     * @return 释放的尝试数量
     */
    @Update("UPDATE research_node_attempt SET status = 'READY', worker_id = NULL, lease_until = NULL " +
            "WHERE status = 'RUNNING' AND lease_until < #{now}")
    int releaseExpiredLeases(@Param("now") LocalDateTime now);

    /**
     * 查询指定 Run 下的所有尝试记录
     *
     * @param runId 运行批次 Run ID
     * @return 尝试记录列表
     */
    @Select("SELECT * FROM research_node_attempt WHERE run_id = #{runId} ORDER BY node_id, attempt")
    List<NodeAttemptPO> findByRun(@Param("runId") UUID runId);

    /**
     * 查询具体的某次节点尝试
     *
     * @param runId   运行批次 Run ID
     * @param nodeId  节点 ID
     * @param attempt 尝试序号
     * @return 节点尝试实体
     */
    @Select("SELECT * FROM research_node_attempt WHERE run_id = #{runId} AND node_id = #{nodeId} AND attempt = #{attempt}")
    NodeAttemptPO findSpecificAttempt(@Param("runId") UUID runId, @Param("nodeId") String nodeId,
                                      @Param("attempt") Integer attempt);
}
