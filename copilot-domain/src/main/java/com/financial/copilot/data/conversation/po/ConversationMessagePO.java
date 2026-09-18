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
 * <h1>研报会话消息持久化实体 (Conversation Message PO)</h1>
 * <p>
 * 对应数据库物理表: {@code conversation_message}
 * 记录投研交互中单次运行 (Run) 产生的有序消息流，包含用户输入、智能体回复及状态追踪。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("conversation_message")
public class ConversationMessagePO {

    /**
     * 自增主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属会话 UUID
     */
    private UUID conversationId;

    /**
     * 所属系统用户 ID
     */
    private Long userId;

    /**
     * 所属多智能体协同运行批次 Run ID
     */
    private UUID runId;

    /**
     * 会话内严格单调递增的序号，用于消息时序复原
     */
    private Long sequenceNo;

    /**
     * 消息发送角色：USER(用户), ASSISTANT(投研助手), SYSTEM(系统预设)
     */
    private String role;

    /**
     * 消息状态：RUNNING(生成中), COMPLETED(已完成), FAILED(失败), CANCELLED(已取消)
     */
    private String status;

    /**
     * 消息正文文本内容 (Markdown / PlainText)
     */
    private String content;

    /**
     * 错误代码（执行失败或超时时的错误码）
     */
    private String errorCode;

    /**
     * 详细错误描述信息
     */
    private String errorMessage;

    /**
     * 结构化元数据（JSONB，包含使用的模型、耗时、Token统计、引用图表等）
     */
    private String metadata;

    /**
     * 消息创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 消息处理完成时间
     */
    private LocalDateTime completedAt;
}
