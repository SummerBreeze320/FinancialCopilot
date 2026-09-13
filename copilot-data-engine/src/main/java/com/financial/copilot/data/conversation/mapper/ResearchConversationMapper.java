package com.financial.copilot.data.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.conversation.po.ResearchConversationPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Mapper
public interface ResearchConversationMapper extends BaseMapper<ResearchConversationPO> {

    @Select("SELECT * FROM research_conversation WHERE user_id = #{userId} AND id = #{conversationId} AND status = 'ACTIVE' AND deleted_at IS NULL FOR UPDATE")
    ResearchConversationPO lockActive(@Param("userId") Long userId, @Param("conversationId") UUID conversationId);

    @Select("SELECT * FROM research_conversation WHERE user_id = #{userId} AND id = #{conversationId} AND deleted_at IS NULL")
    ResearchConversationPO findOwned(@Param("userId") Long userId, @Param("conversationId") UUID conversationId);

    @Update("UPDATE research_conversation SET last_message_at = #{at}, updated_at = #{at} WHERE id = #{conversationId}")
    int touchLastMessage(@Param("conversationId") UUID conversationId, @Param("at") LocalDateTime at);

    @Update("UPDATE research_conversation SET status = 'ARCHIVED', updated_at = #{at} WHERE user_id = #{userId} AND id = #{conversationId} AND status = 'ACTIVE' AND deleted_at IS NULL")
    int archive(@Param("userId") Long userId, @Param("conversationId") UUID conversationId, @Param("at") LocalDateTime at);

    @Select("SELECT * FROM research_conversation WHERE user_id = #{userId} AND deleted_at IS NULL " +
            "AND (#{cursorAt} IS NULL OR last_message_at < #{cursorAt} " +
            "OR (last_message_at = #{cursorAt} AND id < #{cursorId})) " +
            "ORDER BY last_message_at DESC, id DESC LIMIT #{limit}")
    List<ResearchConversationPO> listConversations(@Param("userId") Long userId,
            @Param("cursorAt") LocalDateTime cursorAt, @Param("cursorId") UUID cursorId, @Param("limit") int limit);
}
