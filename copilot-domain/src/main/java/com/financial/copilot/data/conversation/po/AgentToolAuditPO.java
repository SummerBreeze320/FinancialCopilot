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
 * <h1>智能体工具调用审计持久化实体 (Agent Tool Audit PO)</h1>
 * <p>
 * 对应数据库物理表: {@code agent_tool_audit}
 * 全面记录大模型/智能体在思考链及图执行流中所调用的各类工具（MCP、HTTP、Expo、Local）的入参、出参摘要、耗时及状态。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_tool_audit")
public class AgentToolAuditPO {

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
     * 关联的 Assistant 消息 ID
     */
    private Long assistantMessageId;

    /**
     * 系统用户 ID
     */
    private Long userId;

    /**
     * 多智能体执行批次 Run ID
     */
    private UUID runId;

    /**
     * DAG 图执行流节点 ID (如 node_analyzer, node_comparator)
     */
    private String nodeId;

    /**
     * 发起调用的智能体名称 (如 FundAnalyzerAgent, FundComparatorAgent)
     */
    private String agentName;

    /**
     * 工具调用全局唯一标识 ToolCallId
     */
    private String toolCallId;

    /**
     * 被调用的工具唯一名称 (如 fund_analysis_profile, compare_similar)
     */
    private String toolName;

    /**
     * 工具调用状态：RUNNING(执行中), SUCCESS(成功), FAILED(失败), CANCELLED(已取消)
     */
    private String status;

    /**
     * 工具调用输入参数（JSONB 序列化字符串）
     */
    private String arguments;

    /**
     * 工具执行返回结果的结构化/蒸馏摘要
     */
    private String resultSummary;

    /**
     * 执行结果 SHA-256 哈希值，用于幂等校验与缓存比对
     */
    private String resultHash;

    /**
     * 产生的可视化产物或报告组件 ID 列表（JSONB 数组）
     */
    private String artifactIds;

    /**
     * 工具调用发起时间
     */
    private LocalDateTime startedAt;

    /**
     * 工具调用完成时间
     */
    private LocalDateTime completedAt;

    /**
     * 工具调用执行耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 错误代码（若执行失败）
     */
    private String errorCode;

    /**
     * 详细错误信息（若执行失败）
     */
    private String errorMessage;
}
