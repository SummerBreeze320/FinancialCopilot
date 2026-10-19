package com.financial.copilot.data.execution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.execution.po.ResearchRunStatePO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <h1>集群化运行执行状态数据访问映射器 (MyBatis-Plus Mapper)</h1>
 * <p>
 * 提供针对 {@code research_run_state} 表的运行初始化、乐观锁状态快照流转与取消控制。
 * </p>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface ResearchRunStateMapper extends BaseMapper<ResearchRunStatePO> {

    /**
     * 插入新的运行状态记录（包含图快照 JSONB 格式绑定，冲突时忽略）
     *
     * @param po 运行状态持久化实体
     * @return 影响行数
     */
    @Insert("INSERT INTO research_run_state (run_id, user_id, conversation_id, status, graph_snapshot, version, created_at, updated_at) " +
            "VALUES (#{runId}, #{userId}, #{conversationId}, #{status}, CAST(#{graphSnapshot} AS jsonb), #{version}, #{createdAt}, #{updatedAt}) " +
            "ON CONFLICT (run_id) DO UPDATE SET " +
            "status = EXCLUDED.status, graph_snapshot = EXCLUDED.graph_snapshot, " +
            "version = research_run_state.version + 1, updated_at = EXCLUDED.updated_at")
    int upsertState(ResearchRunStatePO po);

    /**
     * 乐观锁更新运行状态及图快照（仅当版本号完全一致时更新成功）
     *
     * @param userId          系统用户 ID
     * @param runId           运行批次 Run ID
     * @param status          目标流转状态
     * @param graphSnapshot   最新的图快照 JSON
     * @param expectedVersion 期望的乐观锁版本
     * @param updatedAt       更新时间戳
     * @return 影响行数（1 表示成功，0 表示版本冲突）
     */
    @Update("UPDATE research_run_state SET status = #{status}, graph_snapshot = CAST(#{graphSnapshot} AS jsonb), " +
            "version = version + 1, updated_at = #{updatedAt} " +
            "WHERE user_id = #{userId} AND run_id = #{runId} AND version = #{expectedVersion}")
    int updateStateOptimistic(@Param("userId") Long userId, @Param("runId") UUID runId,
                              @Param("status") String status, @Param("graphSnapshot") String graphSnapshot,
                              @Param("expectedVersion") Long expectedVersion, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 标记运行状态为 CANCELLED
     *
     * @param userId    系统用户 ID
     * @param runId     运行批次 Run ID
     * @param updatedAt 取消时间戳
     * @return 影响行数
     */
    @Update("UPDATE research_run_state SET status = 'CANCELLED', version = version + 1, updated_at = #{updatedAt} " +
            "WHERE user_id = #{userId} AND run_id = #{runId} AND status IN ('PENDING', 'RUNNING')")
    int cancelRun(@Param("userId") Long userId, @Param("runId") UUID runId, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 查询指定用户下的运行状态记录
     *
     * @param userId 系统用户 ID
     * @param runId  运行批次 Run ID
     * @return 运行状态实体
     */
    @Select("SELECT * FROM research_run_state WHERE user_id = #{userId} AND run_id = #{runId}")
    ResearchRunStatePO findByUserAndRun(@Param("userId") Long userId, @Param("runId") UUID runId);

    /**
     * 根据 Run ID 直接查询运行状态
     *
     * @param runId 运行批次 Run ID
     * @return 运行状态实体
     */
    @Select("SELECT * FROM research_run_state WHERE run_id = #{runId}")
    ResearchRunStatePO findByRun(@Param("runId") UUID runId);
}
