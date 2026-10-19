package com.financial.copilot.data.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.billing.po.PointReservationPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

/**
 * <h1>积分预占持久层 Mapper (MyBatis-Plus)</h1>
 * <p>
 * 提供基于 workflowRunId 的原子预占、状态流转与幂等防重查询。
 * </p>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface PointReservationMapper extends BaseMapper<PointReservationPO> {

    /**
     * 插入预占记录（基于 workflow_run_id 唯一约束幂等忽略冲突）
     *
     * @param userId 系统用户 ID
     * @param workflowRunId 运行唯一标识
     * @param estimatedPoints 预估冻结积分
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @return 影响行数 (1 成功, 0 已存在冲突)
     */
    @Insert("INSERT INTO sys_point_reservation " +
            "(user_id, workflow_run_id, estimated_points, actual_points, status, created_at, updated_at) " +
            "VALUES (#{userId}, #{workflowRunId}, #{estimatedPoints}, 0, 'ACTIVE', #{createdAt}, #{updatedAt}) " +
            "ON CONFLICT (workflow_run_id) DO NOTHING")
    int insertIfAbsent(@Param("userId") Long userId,
                       @Param("workflowRunId") String workflowRunId,
                       @Param("estimatedPoints") Long estimatedPoints,
                       @Param("createdAt") Instant createdAt,
                       @Param("updatedAt") Instant updatedAt);

    /**
     * 根据运行唯一标识查询预占记录
     *
     * @param workflowRunId 运行唯一标识
     * @return 预占 PO
     */
    @Select("SELECT * FROM sys_point_reservation WHERE workflow_run_id = #{workflowRunId}")
    PointReservationPO selectByWorkflowRunId(@Param("workflowRunId") String workflowRunId);

    /**
     * 加悲观行锁查询预占记录
     *
     * @param workflowRunId 运行唯一标识
     * @return 预占 PO
     */
    @Select("SELECT * FROM sys_point_reservation WHERE workflow_run_id = #{workflowRunId} FOR UPDATE")
    PointReservationPO selectForUpdate(@Param("workflowRunId") String workflowRunId);

    /**
     * 仅当预占状态为 ACTIVE 时原子转为 SETTLED 状态并更新实际积分
     *
     * @param userId 系统用户 ID
     * @param workflowRunId 运行唯一标识
     * @param actualPoints 实际消费积分
     * @param updatedAt 更新时间
     * @return 影响行数 (1 成功结算, 0 已结算或状态非 ACTIVE)
     */
    @Update("UPDATE sys_point_reservation SET actual_points = #{actualPoints}, status = 'SETTLED', updated_at = #{updatedAt} " +
            "WHERE user_id = #{userId} AND workflow_run_id = #{workflowRunId} AND status = 'ACTIVE'")
    int settleIfActive(@Param("userId") Long userId,
                       @Param("workflowRunId") String workflowRunId,
                       @Param("actualPoints") Long actualPoints,
                       @Param("updatedAt") Instant updatedAt);

    /**
     * 仅当预占状态为 ACTIVE 时原子转为 RELEASED 状态
     *
     * @param userId 系统用户 ID
     * @param workflowRunId 运行唯一标识
     * @param updatedAt 更新时间
     * @return 影响行数 (1 成功释放, 0 已结算/释放或状态非 ACTIVE)
     */
    @Update("UPDATE sys_point_reservation SET status = 'RELEASED', updated_at = #{updatedAt} " +
            "WHERE user_id = #{userId} AND workflow_run_id = #{workflowRunId} AND status = 'ACTIVE'")
    int releaseIfActive(@Param("userId") Long userId,
                        @Param("workflowRunId") String workflowRunId,
                        @Param("updatedAt") Instant updatedAt);
}
