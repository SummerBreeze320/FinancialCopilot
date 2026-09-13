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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("conversation_message")
public class ConversationMessagePO {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private UUID conversationId;
    private Long userId;
    private UUID runId;
    private Long sequenceNo;
    private String role;
    private String status;
    private String content;
    private String errorCode;
    private String errorMessage;
    private String metadata;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
