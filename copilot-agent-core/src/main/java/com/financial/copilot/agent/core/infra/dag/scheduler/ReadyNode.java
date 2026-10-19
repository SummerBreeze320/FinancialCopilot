package com.financial.copilot.agent.core.infra.dag.scheduler;

import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.infra.dag.runtime.resource.NodePriority;
import com.financial.copilot.agent.core.infra.dag.runtime.resource.ResourceType;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * <h1>全局调度器就绪节点元数据 (Ready Node)</h1>
 * <p>
 * 封装已满足 DAG 上游依赖关系、等待获取物理配额并发派发执行的节点实例。
 * 携带租户、用户、Run、会话隔离标识及老化时间戳，用于全局分层公平调度。
 * </p>
 *
 * @param tenantId          租户标识
 * @param userId            用户唯一 ID
 * @param runId             所属图运行唯一 ID
 * @param conversationId    会话唯一 ID
 * @param nodeId            图节点标识
 * @param priority          静态声明优先级 (HIGH, NORMAL, LOW)
 * @param readyAt           进入就绪状态的时间戳
 * @param resources         执行所需的物理资源信号量配额
 * @param dispatchAction    获得许可后触发执行的回调动作
 * @param cancellationToken 级联取消令牌
 */
public record ReadyNode(
        String tenantId,
        Long userId,
        String runId,
        UUID conversationId,
        String nodeId,
        NodePriority priority,
        Instant readyAt,
        Map<ResourceType, Integer> resources,
        Runnable dispatchAction,
        CancellationToken cancellationToken
) {

    public ReadyNode {
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }
        if (userId == null) {
            userId = 0L;
        }
        if (priority == null) {
            priority = NodePriority.NORMAL;
        }
        if (readyAt == null) {
            readyAt = Instant.now();
        }
        if (resources == null) {
            resources = Map.of(ResourceType.AGENT, 1);
        } else {
            resources = Collections.unmodifiableMap(resources);
        }
    }

    /**
     * 计算指定时间点的有效动态调度优先级 (考虑时间老化惩罚/加权)
     * <p>
     * 公式：权重 * 1,000 + min(等待秒数, 999)
     * </p>
     *
     * @param now 当前时间戳
     * @return 动态有效优先级得分
     */
    public long effectivePriority(Instant now) {
        long ageSeconds = Math.max(0, Duration.between(readyAt, now).toSeconds());
        return priority.getWeight() * 1_000L + Math.min(ageSeconds, 999L);
    }
}
