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

/**
 * <h1>研报会话数据访问映射器 (MyBatis-Plus Mapper)</h1>
 * <p>
 * 提供针对 {@code research_conversation} 表的高并发悲观行锁、活跃会话归档及游标分页查询能力。
 * </p>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface ResearchConversationMapper extends BaseMapper<ResearchConversationPO> {

    /**
     * 悲观锁锁定并获取指定用户的活跃会话 (SELECT ... FOR UPDATE)
     *
     * @param userId         所属系统用户 ID
     * @param conversationId 会话 UUID
     * @return 锁定的会话实体，不存在或非活跃返回 null
     */
    @Select("SELECT * FROM research_conversation WHERE user_id = #{userId} AND id = #{conversationId} AND status = 'ACTIVE' AND deleted_at IS NULL FOR UPDATE")
    ResearchConversationPO lockActive(@Param("userId") Long userId, @Param("conversationId") UUID conversationId);

    /**
     * 查询指定用户拥有的有效会话（未被软删除）
     *
     * @param userId         所属系统用户 ID
     * @param conversationId 会话 UUID
     * @return 会话实体
     */
    @Select("SELECT * FROM research_conversation WHERE user_id = #{userId} AND id = #{conversationId} AND deleted_at IS NULL")
    ResearchConversationPO findOwned(@Param("userId") Long userId, @Param("conversationId") UUID conversationId);

    /**
     * 更新会话的最新消息时间戳与修改时间
     *
     * @param conversationId 会话 UUID
     * @param at             更新时间点
     * @return 影响行数
     */
    @Update("UPDATE research_conversation SET last_message_at = #{at}, updated_at = #{at} WHERE id = #{conversationId}")
    int touchLastMessage(@Param("conversationId") UUID conversationId, @Param("at") LocalDateTime at);

    /**
     * 将指定活跃会话归档
     *
     * @param userId         所属系统用户 ID
     * @param conversationId 会话 UUID
     * @param at             归档时间点
     * @return 影响行数
     */
    @Update("UPDATE research_conversation SET status = 'ARCHIVED', updated_at = #{at} WHERE user_id = #{userId} AND id = #{conversationId} AND status = 'ACTIVE' AND deleted_at IS NULL")
    int archive(@Param("userId") Long userId, @Param("conversationId") UUID conversationId, @Param("at") LocalDateTime at);

    /**
     * 基于游标 (last_message_at, id) 倒序分页查询用户的会话列表
     *
     * @param userId   所属系统用户 ID
     * @param cursorAt 游标消息时间
     * @param cursorId 游标会话 UUID
     * @param limit    拉取最大条数
     * @return 会话持久化对象列表
     */
    @Select("SELECT * FROM research_conversation WHERE user_id = #{userId} AND deleted_at IS NULL " +
            "AND (#{cursorAt} IS NULL OR last_message_at < #{cursorAt} " +
            "OR (last_message_at = #{cursorAt} AND id < #{cursorId})) " +
            "ORDER BY last_message_at DESC, id DESC LIMIT #{limit}")
    List<ResearchConversationPO> listConversations(@Param("userId") Long userId,
            @Param("cursorAt") LocalDateTime cursorAt, @Param("cursorId") UUID cursorId, @Param("limit") int limit);
}
