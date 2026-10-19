package com.financial.copilot.data.billing.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financial.copilot.domain.platform.billing.entity.PointReservation;
import com.financial.copilot.domain.platform.billing.entity.ReservationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * <h1>积分预占持久化对象 (Point Reservation PO)</h1>
 * <p>
 * 映射数据库表 {@code sys_point_reservation}。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_point_reservation")
public class PointReservationPO {

    /**
     * 自增主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 系统用户 ID
     */
    private Long userId;

    /**
     * 投研运行唯一标识
     */
    private String workflowRunId;

    /**
     * 预估冻结积分
     */
    private Long estimatedPoints;

    /**
     * 实际消费结算积分
     */
    private Long actualPoints;

    /**
     * 预占状态 (ACTIVE, SETTLED, RELEASED)
     */
    private String status;

    /**
     * 创建时间戳
     */
    private Instant createdAt;

    /**
     * 更新时间戳
     */
    private Instant updatedAt;

    public PointReservation toDomain() {
        return new PointReservation(
                id,
                userId,
                workflowRunId,
                estimatedPoints != null ? estimatedPoints : 0L,
                actualPoints != null ? actualPoints : 0L,
                status != null ? ReservationStatus.valueOf(status) : ReservationStatus.ACTIVE,
                createdAt != null ? createdAt : Instant.now(),
                updatedAt != null ? updatedAt : Instant.now()
        );
    }

    public static PointReservationPO fromDomain(PointReservation domain) {
        if (domain == null) {
            return null;
        }
        return PointReservationPO.builder()
                .id(domain.id())
                .userId(domain.userId())
                .workflowRunId(domain.workflowRunId())
                .estimatedPoints(domain.estimatedPoints())
                .actualPoints(domain.actualPoints())
                .status(domain.status().name())
                .createdAt(domain.createdAt())
                .updatedAt(domain.updatedAt())
                .build();
    }
}
