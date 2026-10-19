package com.financial.copilot.data.execution.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <h1>节点执行尝试与分布式租约持久化实体 (Node Attempt PO)</h1>
 * <p>
 * 对应数据库物理表: {@code research_node_attempt}
 * 复合主键为 (run_id, node_id, attempt)，支持基于租约的工作窃取与幂等恢复。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("research_node_attempt")
public class NodeAttemptPO {

    /**
     * 运行唯一标识 UUID
     */
    private UUID runId;

    /**
     * DAG 图节点唯一标识
     */
    private String nodeId;

    /**
     * 节点执行尝试序号 (从 1 开始递增)
     */
    private Integer attempt;

    /**
     * 执行尝试状态：READY, RUNNING, SUCCEEDED, FAILED, CANCELLED, EXPIRED
     */
    private String status;

    /**
     * 持有租约的工作节点标识 WorkerId
     */
    private String workerId;

    /**
     * 租约截止有效期
     */
    private LocalDateTime leaseUntil;

    /**
     * 节点执行产物 (JSONB)
     */
    private String artifact;
}
