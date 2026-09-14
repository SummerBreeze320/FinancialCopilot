package com.financial.copilot.data.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.conversation.po.AgentToolAuditPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * <h1>智能体工具调用审计数据访问映射器 (MyBatis-Plus Mapper)</h1>
 * <p>
 * 提供针对 {@code agent_tool_audit} 表的工具调用生命周期（启动/完成/取消）追踪与入库审计能力。
 * </p>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface AgentToolAuditMapper extends BaseMapper<AgentToolAuditPO> {

    /**
     * 记录工具调用开始执行事件（包含入参 JSONB 及幂等去重）
     *
     * @param po 审计实体
     * @return 影响行数
     */
    @Insert("INSERT INTO agent_tool_audit (conversation_id, assistant_message_id, user_id, run_id, node_id, agent_name, tool_call_id, tool_name, status, arguments, started_at) " +
            "VALUES (#{conversationId}, #{assistantMessageId}, #{userId}, #{runId}, #{nodeId}, #{agentName}, #{toolCallId}, #{toolName}, #{status}, CAST(#{arguments} AS jsonb), #{startedAt}) " +
            "ON CONFLICT (user_id, run_id, tool_call_id) DO NOTHING")
    int start(AgentToolAuditPO po);

    /**
     * 记录工具调用正常完成或异常结束（更新出参摘要、哈希、产物及耗时）
     *
     * @param userId       系统用户 ID
     * @param runId        运行批次 Run ID
     * @param toolCallId   工具调用唯一标识
     * @param status       最终状态 (SUCCESS / FAILED)
     * @param summary      执行结果精简摘要
     * @param hash         结果数据哈希值
     * @param artifactIds  关联的可视化产物 ID 列表 (JSONB)
     * @param errorCode    错误代码
     * @param errorMessage 详细错误信息
     * @param at           完成时间点
     * @param durationMs   耗时（毫秒）
     * @return 影响行数
     */
    @Update("UPDATE agent_tool_audit SET status = #{status}, result_summary = #{summary}, result_hash = #{hash}, " +
            "artifact_ids = CAST(#{artifactIds} AS jsonb), error_code = #{errorCode}, error_message = #{errorMessage}, " +
            "completed_at = #{at}, duration_ms = #{durationMs} " +
            "WHERE user_id = #{userId} AND run_id = #{runId} AND tool_call_id = #{toolCallId} AND status = 'RUNNING'")
    int complete(@Param("userId") Long userId, @Param("runId") UUID runId, @Param("toolCallId") String toolCallId,
                 @Param("status") String status, @Param("summary") String summary, @Param("hash") String hash,
                 @Param("artifactIds") String artifactIds, @Param("errorCode") String errorCode,
                 @Param("errorMessage") String errorMessage, @Param("at") LocalDateTime at, @Param("durationMs") long durationMs);

    /**
     * 批量取消指定 Run 批次下仍在 RUNNING 状态的遗留工具调用
     *
     * @param userId       系统用户 ID
     * @param runId        运行批次 Run ID
     * @param status       取消状态 (CANCELLED)
     * @param errorCode    错误代码
     * @param errorMessage 取消原因描述
     * @param at           取消时间点
     * @return 影响行数
     */
    @Update("UPDATE agent_tool_audit SET status = #{status}, error_code = #{errorCode}, error_message = #{errorMessage}, " +
            "completed_at = #{at} WHERE user_id = #{userId} AND run_id = #{runId} AND status = 'RUNNING'")
    int cancelOpenForRun(@Param("userId") Long userId, @Param("runId") UUID runId,
                         @Param("status") String status, @Param("errorCode") String errorCode,
                         @Param("errorMessage") String errorMessage, @Param("at") LocalDateTime at);

    /**
     * 分页拉取指定 Run 批次产生的所有工具审计明细
     *
     * @param userId   系统用户 ID
     * @param runId    运行批次 Run ID
     * @param beforeId 游标 ID (查询小于该 ID 的记录)
     * @param limit    拉取最大条数
     * @return 审计记录列表
     */
    @Select("SELECT * FROM agent_tool_audit WHERE user_id = #{userId} AND run_id = #{runId} " +
            "AND (#{beforeId} IS NULL OR id < #{beforeId}) ORDER BY id DESC LIMIT #{limit}")
    List<AgentToolAuditPO> listByRun(@Param("userId") Long userId, @Param("runId") UUID runId,
                                     @Param("beforeId") Long beforeId, @Param("limit") int limit);
}
