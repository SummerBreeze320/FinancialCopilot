package com.financial.copilot.data.conversation.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <h1>金融研报对话会话持久化实体 (Research Conversation PO)</h1>
 * <p>
 * 对应数据库物理表: {@code research_conversation}
 * 记录用户与金融投研智能体交互的主会话元数据，支持软删除和游标分页查询。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("research_conversation")
public class ResearchConversationPO {

    /**
     * 会话唯一标识 UUID（主键，客户端或系统生成）
     */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /**
     * 所属系统用户 ID
     */
    private Long userId;

    /**
     * 会话标题（由首条 Prompt 自动提炼或用户自定义）
     */
    private String title;

    /**
     * 会话状态：ACTIVE(进行中), ARCHIVED(已归档), DELETED(已删除)
     */
    private String status;

    /**
     * 最近一条消息的产生时间（用于会话列表排序）
     */
    private LocalDateTime lastMessageAt;

    /**
     * 会话创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 会话最后更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 软删除时间戳（为 NULL 表示未删除）
     */
    private LocalDateTime deletedAt;
}
