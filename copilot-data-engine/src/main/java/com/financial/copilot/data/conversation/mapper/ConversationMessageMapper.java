package com.financial.copilot.data.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.conversation.po.ConversationMessagePO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessagePO> {

    @Select("SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM conversation_message WHERE conversation_id = #{conversationId}")
    Long nextSequence(@Param("conversationId") UUID conversationId);

    @Insert("INSERT INTO conversation_message (conversation_id, user_id, run_id, sequence_no, role, status, content, metadata, created_at, completed_at) " +
            "VALUES (#{conversationId}, #{userId}, #{runId}, #{sequenceNo}, #{role}, #{status}, #{content}, CAST(#{metadata} AS jsonb), #{createdAt}, #{completedAt})")
    int insertMessage(ConversationMessagePO po);

    @Update("UPDATE conversation_message SET status = 'COMPLETED', content = #{content}, metadata = CAST(#{metadata} AS jsonb), completed_at = #{at} " +
            "WHERE user_id = #{userId} AND run_id = #{runId} AND role = 'ASSISTANT' AND status = 'RUNNING'")
    int completeAssistant(@Param("userId") Long userId, @Param("runId") UUID runId,
                         @Param("content") String content, @Param("metadata") String metadata, @Param("at") LocalDateTime at);

    @Update("UPDATE conversation_message SET status = 'FAILED', error_code = #{errorCode}, error_message = #{errorMessage}, completed_at = #{at} " +
            "WHERE user_id = #{userId} AND run_id = #{runId} AND role = 'ASSISTANT' AND status = 'RUNNING'")
    int failAssistant(@Param("userId") Long userId, @Param("runId") UUID runId,
                     @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage, @Param("at") LocalDateTime at);

    @Update("UPDATE conversation_message SET status = 'CANCELLED', error_code = 'CANCELLED', error_message = #{message}, completed_at = #{at} " +
            "WHERE user_id = #{userId} AND run_id = #{runId} AND role = 'ASSISTANT' AND status = 'RUNNING'")
    int cancelAssistant(@Param("userId") Long userId, @Param("runId") UUID runId,
                       @Param("message") String message, @Param("at") LocalDateTime at);

    @Select("SELECT * FROM conversation_message WHERE user_id = #{userId} AND run_id = #{runId} ORDER BY sequence_no")
    List<ConversationMessagePO> findByRun(@Param("userId") Long userId, @Param("runId") UUID runId);

    @Select("SELECT * FROM conversation_message WHERE user_id = #{userId} AND run_id = #{runId} AND role = 'ASSISTANT'")
    ConversationMessagePO findAssistantByRun(@Param("userId") Long userId, @Param("runId") UUID runId);

    @Select("SELECT * FROM conversation_message WHERE conversation_id = #{conversationId} AND user_id = #{userId} " +
            "AND (#{beforeSequence} IS NULL OR sequence_no < #{beforeSequence}) " +
            "ORDER BY sequence_no DESC LIMIT #{limit}")
    List<ConversationMessagePO> listMessages(@Param("userId") Long userId, @Param("conversationId") UUID conversationId,
                                              @Param("beforeSequence") Long beforeSequence, @Param("limit") int limit);

    @Select("SELECT * FROM conversation_message WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND status = 'COMPLETED' " +
            "ORDER BY sequence_no DESC LIMIT #{limit}")
    List<ConversationMessagePO> recentCompletedMessages(@Param("userId") Long userId, @Param("conversationId") UUID conversationId,
                                                         @Param("limit") int limit);
}
