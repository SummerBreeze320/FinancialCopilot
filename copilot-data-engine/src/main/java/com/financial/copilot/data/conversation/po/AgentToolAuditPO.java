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
@TableName("agent_tool_audit")
public class AgentToolAuditPO {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private UUID conversationId;
    private Long assistantMessageId;
    private Long userId;
    private UUID runId;
    private String nodeId;
    private String agentName;
    private String toolCallId;
    private String toolName;
    private String status;
    private String arguments;
    private String resultSummary;
    private String resultHash;
    private String artifactIds;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long durationMs;
    private String errorCode;
    private String errorMessage;
}
