package com.financial.copilot.data.execution.po;

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
 * <h1>运行流事件持久化实体 (Research Run Event PO)</h1>
 * <p>
 * 对应数据库物理表: {@code research_run_event}
 * 复合主键为 (run_id, sequence_no)，保证单次运行事件流严格单调递增，支持 SSE 续传。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("research_run_event")
public class ResearchRunEventPO {

    /**
     * 运行唯一标识 UUID
     */
    @TableId(value = "run_id", type = IdType.INPUT)
    private UUID runId;

    /**
     * 所属系统用户 ID
     */
    private Long userId;

    /**
     * 事件单调递增序号 (从 1 开始递增)
     */
    private Long sequenceNo;

    /**
     * 事件类型 (如 run_started, node_started, token_delta, run_completed)
     */
    private String eventType;

    /**
     * 事件完整载荷 JSON 串 (JSONB)
     */
    private String payload;

    /**
     * 事件发生时间戳
     */
    private LocalDateTime createdAt;
}
