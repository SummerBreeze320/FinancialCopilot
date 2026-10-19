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
 * <h1>集群化运行执行状态持久化实体 (Research Run State PO)</h1>
 * <p>
 * 对应数据库物理表: {@code research_run_state}
 * 记录投研图执行的集群状态、图拓扑快照及乐观锁版本。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("research_run_state")
public class ResearchRunStatePO {

    /**
     * 运行唯一标识 UUID (主键)
     */
    @TableId(value = "run_id", type = IdType.INPUT)
    private UUID runId;

    /**
     * 所属系统用户 ID
     */
    private Long userId;

    /**
     * 所属研报会话 UUID
     */
    private UUID conversationId;

    /**
     * 运行状态：PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED
     */
    private String status;

    /**
     * 执行图拓扑结构及节点状态快照 (JSONB)
     */
    private String graphSnapshot;

    /**
     * 乐观锁版本号 (从 0 开始递增)
     */
    private Long version;

    /**
     * 创建时间戳
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间戳
     */
    private LocalDateTime updatedAt;
}
