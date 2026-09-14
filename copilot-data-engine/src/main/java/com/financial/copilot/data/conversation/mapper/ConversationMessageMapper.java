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

/**
 * <h1>研报会话消息数据访问映射器 (MyBatis-Plus Mapper)</h1>
 * <p>
 * 提供针对 {@code conversation_message} 表的序号自增计算、状态流转（完成/失败/取消）与时序列表查询功能。
 * </p>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessagePO> {

    /**
     * 计算并获取指定会话下一条消息的严格单调递增序号
     *
     * @param conversationId 会话 UUID
     * @return 下一个序列号
     */
    @Select("SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM conversation_message WHERE conversation_id = #{conversationId}")
    Long nextSequence(@Param("conversationId") UUID conversationId);

    /**
     * 插入新的会话消息记录（支持 JSONB 格式元数据绑定）
     *
     * @param po 消息实体
     * @return 影响行数
     */
    @Insert("INSERT INTO conversation_message (conversation_id, user_id, run_id, sequence_no, role, status, content, metadata, created_at, completed_at) " +
            "VALUES (#{conversationId}, #{userId}, #{runId}, #{sequenceNo}, #{role}, #{status}, #{content}, CAST(#{metadata} AS jsonb), #{createdAt}, #{completedAt})")
    int insertMessage(ConversationMessagePO po);

    /**
     * 将处于 RUNNING 状态的 ASSISTANT 消息标记为 COMPLETED 完成状态
     *
     * @param userId   系统用户 ID
     * @param runId    运行批次 Run ID
     * @param content  最终生成的回答文本
     * @param metadata 结构化执行元数据（JSONB）
     * @param at       完成时间戳
     * @return 影响行数
     */
    @Update("UPDATE conversation_message SET status = 'COMPLETED', content = #{content}, metadata = CAST(#{metadata} AS jsonb), completed_at = #{at} " +
            "WHERE user_id = #{userId} AND run_id = #{runId} AND role = 'ASSISTANT' AND status = 'RUNNING'")
    int completeAssistant(@Param("userId") Long userId, @Param("runId") UUID runId,
                         @Param("content") String content, @Param("metadata") String metadata, @Param("at") LocalDateTime at);

    /**
     * 将处于 RUNNING 状态的 ASSISTANT 消息标记为 FAILED 失败状态
     *
     * @param userId       系统用户 ID
     * @param runId        运行批次 Run ID
     * @param errorCode    错误码
     * @param errorMessage 错误描述
     * @param at           失败时间戳
     * @return 影响行数
     */
    @Update("UPDATE conversation_message SET status = 'FAILED', error_code = #{errorCode}, error_message = #{errorMessage}, completed_at = #{at} " +
            "WHERE user_id = #{userId} AND run_id = #{runId} AND role = 'ASSISTANT' AND status = 'RUNNING'")
    int failAssistant(@Param("userId") Long userId, @Param("runId") UUID runId,
                     @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage, @Param("at") LocalDateTime at);

    /**
     * 将处于 RUNNING 状态的 ASSISTANT 消息标记为 CANCELLED 取消状态
     *
     * @param userId  系统用户 ID
     * @param runId   运行批次 Run ID
     * @param message 取消原因说明
     * @param at      取消时间戳
     * @return 影响行数
     */
    @Update("UPDATE conversation_message SET status = 'CANCELLED', error_code = 'CANCELLED', error_message = #{message}, completed_at = #{at} " +
            "WHERE user_id = #{userId} AND run_id = #{runId} AND role = 'ASSISTANT' AND status = 'RUNNING'")
    int cancelAssistant(@Param("userId") Long userId, @Param("runId") UUID runId,
                       @Param("message") String message, @Param("at") LocalDateTime at);

    /**
     * 查询指定单次 Run 执行产生的所有有序消息
     *
     * @param userId 系统用户 ID
     * @param runId  运行批次 Run ID
     * @return 消息列表（按 sequence_no 升序）
     */
    @Select("SELECT * FROM conversation_message WHERE user_id = #{userId} AND run_id = #{runId} ORDER BY sequence_no")
    List<ConversationMessagePO> findByRun(@Param("userId") Long userId, @Param("runId") UUID runId);

    /**
     * 查询指定 Run 批次中的 ASSISTANT 回复消息
     *
     * @param userId 系统用户 ID
     * @param runId  运行批次 Run ID
     * @return 助手消息实体
     */
    @Select("SELECT * FROM conversation_message WHERE user_id = #{userId} AND run_id = #{runId} AND role = 'ASSISTANT'")
    ConversationMessagePO findAssistantByRun(@Param("userId") Long userId, @Param("runId") UUID runId);

    /**
     * 游标向前分页获取指定会话的历史消息
     *
     * @param userId         系统用户 ID
     * @param conversationId 会话 UUID
     * @param beforeSequence 游标序列号（查询小于该序号的消息）
     * @param limit          拉取最大条数
     * @return 消息列表（按 sequence_no 倒序）
     */
    @Select("SELECT * FROM conversation_message WHERE conversation_id = #{conversationId} AND user_id = #{userId} " +
            "AND (#{beforeSequence} IS NULL OR sequence_no < #{beforeSequence}) " +
            "ORDER BY sequence_no DESC LIMIT #{limit}")
    List<ConversationMessagePO> listMessages(@Param("userId") Long userId, @Param("conversationId") UUID conversationId,
                                              @Param("beforeSequence") Long beforeSequence, @Param("limit") int limit);

    /**
     * 获取指定会话最新完成的 N 条有效消息
     *
     * @param userId         系统用户 ID
     * @param conversationId 会话 UUID
     * @param limit          拉取条数
     * @return 消息列表（按 sequence_no 倒序）
     */
    @Select("SELECT * FROM conversation_message WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND status = 'COMPLETED' " +
            "ORDER BY sequence_no DESC LIMIT #{limit}")
    List<ConversationMessagePO> recentCompletedMessages(@Param("userId") Long userId, @Param("conversationId") UUID conversationId,
                                                         @Param("limit") int limit);
}
