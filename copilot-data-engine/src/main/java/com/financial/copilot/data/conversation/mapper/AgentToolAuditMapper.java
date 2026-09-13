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

@Mapper
public interface AgentToolAuditMapper extends BaseMapper<AgentToolAuditPO> {

    @Insert("INSERT INTO agent_tool_audit (conversation_id, assistant_message_id, user_id, run_id, node_id, agent_name, tool_call_id, tool_name, status, arguments, started_at) " +
            "VALUES (#{conversationId}, #{assistantMessageId}, #{userId}, #{runId}, #{nodeId}, #{agentName}, #{toolCallId}, #{toolName}, #{status}, CAST(#{arguments} AS jsonb), #{startedAt}) " +
            "ON CONFLICT (user_id, run_id, tool_call_id) DO NOTHING")
    int start(AgentToolAuditPO po);

    @Update("UPDATE agent_tool_audit SET status = #{status}, result_summary = #{summary}, result_hash = #{hash}, " +
            "artifact_ids = CAST(#{artifactIds} AS jsonb), error_code = #{errorCode}, error_message = #{errorMessage}, " +
            "completed_at = #{at}, duration_ms = #{durationMs} " +
            "WHERE user_id = #{userId} AND run_id = #{runId} AND tool_call_id = #{toolCallId} AND status = 'RUNNING'")
    int complete(@Param("userId") Long userId, @Param("runId") UUID runId, @Param("toolCallId") String toolCallId,
                 @Param("status") String status, @Param("summary") String summary, @Param("hash") String hash,
                 @Param("artifactIds") String artifactIds, @Param("errorCode") String errorCode,
                 @Param("errorMessage") String errorMessage, @Param("at") LocalDateTime at, @Param("durationMs") long durationMs);

    @Update("UPDATE agent_tool_audit SET status = #{status}, error_code = #{errorCode}, error_message = #{errorMessage}, " +
            "completed_at = #{at} WHERE user_id = #{userId} AND run_id = #{runId} AND status = 'RUNNING'")
    int cancelOpenForRun(@Param("userId") Long userId, @Param("runId") UUID runId,
                         @Param("status") String status, @Param("errorCode") String errorCode,
                         @Param("errorMessage") String errorMessage, @Param("at") LocalDateTime at);

    @Select("SELECT * FROM agent_tool_audit WHERE user_id = #{userId} AND run_id = #{runId} " +
            "AND (#{beforeId} IS NULL OR id < #{beforeId}) ORDER BY id DESC LIMIT #{limit}")
    List<AgentToolAuditPO> listByRun(@Param("userId") Long userId, @Param("runId") UUID runId,
                                     @Param("beforeId") Long beforeId, @Param("limit") int limit);
}
