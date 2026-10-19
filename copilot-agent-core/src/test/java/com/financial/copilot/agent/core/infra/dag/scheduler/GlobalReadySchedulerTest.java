package com.financial.copilot.agent.core.infra.dag.scheduler;

import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.infra.dag.runtime.resource.NodePriority;
import com.financial.copilot.agent.core.infra.dag.runtime.resource.ResourceManager;
import com.financial.copilot.agent.core.infra.dag.runtime.resource.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalReadySchedulerTest {

    @Test
    @DisplayName("单用户单 Run 限制测试：单用户不能占满全部 AGENT 许可，受 maxAgentsPerRun 限制且公平轮询其他用户")
    void oneUserCannotOccupyEveryAgentPermit() {
        // 全局 4 个 AGENT 许可
        ResourceManager resources = new ResourceManager(Map.of(ResourceType.AGENT, 4));
        RuntimeCapacityProperties properties = RuntimeCapacityProperties.builder()
                .maxAgentsPerRun(2)
                .build();
        GlobalReadyScheduler scheduler = new GlobalReadyScheduler(resources, properties);

        List<String> dispatchedNodes = new CopyOnWriteArrayList<>();

        // User 1 提交 5 个就绪节点（争抢 AGENT: 1）
        for (int i = 1; i <= 5; i++) {
            String nodeId = "u1_node_" + i;
            scheduler.enqueue(new ReadyNode(
                    "tenant-1", 1L, "run-1", UUID.randomUUID(), nodeId,
                    NodePriority.NORMAL, Instant.now(), Map.of(ResourceType.AGENT, 1),
                    () -> dispatchedNodes.add(nodeId), new CancellationToken("test")
            ), false);
        }

        // User 2 提交 1 个就绪节点
        String u2Node = "u2_node_1";
        scheduler.enqueue(new ReadyNode(
                "tenant-1", 2L, "run-2", UUID.randomUUID(), u2Node,
                NodePriority.NORMAL, Instant.now(), Map.of(ResourceType.AGENT, 1),
                () -> dispatchedNodes.add(u2Node), new CancellationToken("test")
        ), false);

        // 触发调度
        scheduler.schedule();

        // 验证：User 1 的 run-1 活跃 Agent 达到 maxAgentsPerRun (2) 后被阻断，剩余的 AGENT 许可成功派发给 User 2
        assertThat(scheduler.getActiveAgents("run-1")).isEqualTo(2);
        assertThat(dispatchedNodes).contains(u2Node);
        assertThat(dispatchedNodes).hasSize(3); // User 1 派发 2 个，User 2 派发 1 个，共 3 个，剩余 1 个许可
    }

    @Test
    @DisplayName("动态老化加权优先级测试：较低静态优先级的节点因等待时间长，动态有效优先级逐渐增加")
    void lowPriorityNodeRunsBecauseOfAging() {
        ResourceManager resources = new ResourceManager(Map.of(ResourceType.AGENT, 1));
        GlobalReadyScheduler scheduler = new GlobalReadyScheduler(resources, RuntimeCapacityProperties.defaults());

        Instant now = Instant.now();
        Instant olderTime = now.minus(Duration.ofSeconds(100)); // 老化 100 秒

        ReadyNode olderLowNode = new ReadyNode(
                "tenant-1", 1L, "run-1", UUID.randomUUID(), "old_low",
                NodePriority.LOW, olderTime, Map.of(ResourceType.AGENT, 1),
                () -> {}, new CancellationToken("test")
        );

        ReadyNode freshLowNode = new ReadyNode(
                "tenant-1", 1L, "run-1", UUID.randomUUID(), "fresh_low",
                NodePriority.LOW, now, Map.of(ResourceType.AGENT, 1),
                () -> {}, new CancellationToken("test")
        );

        long olderScore = scheduler.effectivePriority(olderLowNode, now);
        long freshScore = scheduler.effectivePriority(freshLowNode, now);

        // LOW 权重为 1：freshScore = 1,000 + 0 = 1,000；olderScore = 1,000 + 100 = 1,100
        assertThat(olderScore).isEqualTo(1100L);
        assertThat(freshScore).isEqualTo(1000L);
        assertThat(olderScore).isGreaterThan(freshScore);

        List<String> dispatched = new ArrayList<>();
        ReadyNode olderWithCallback = new ReadyNode(
                "tenant-1", 1L, "run-1", UUID.randomUUID(), "old_low",
                NodePriority.LOW, olderTime, Map.of(ResourceType.AGENT, 1),
                () -> dispatched.add("old_low"), new CancellationToken("test")
        );
        ReadyNode freshWithCallback = new ReadyNode(
                "tenant-1", 1L, "run-1", UUID.randomUUID(), "fresh_low",
                NodePriority.LOW, now, Map.of(ResourceType.AGENT, 1),
                () -> dispatched.add("fresh_low"), new CancellationToken("test")
        );

        scheduler.enqueue(freshWithCallback, false);
        scheduler.enqueue(olderWithCallback, false);
        scheduler.schedule();

        // 当只有 1 个 AGENT 配额时，老化的节点排在前面被派发！
        assertThat(dispatched).containsExactly("old_low");
    }

    @Test
    @DisplayName("非队头阻塞测试：当高优先级节点所需资源未满足时，自动扫描后续满足资源的候选节点派发")
    void nonHeadOfLineBlockingWhenHeadResourceUnavailable() {
        // 只有 1 个 AGENT 许可，DPU 为 0
        ResourceManager resources = new ResourceManager(Map.of(
                ResourceType.AGENT, 1,
                ResourceType.DPU, 0
        ));
        GlobalReadyScheduler scheduler = new GlobalReadyScheduler(resources, RuntimeCapacityProperties.defaults());

        List<String> dispatched = new ArrayList<>();

        // 队头节点 A 需要 DPU: 1 (HIGH 优先级，但 DPU 不足)
        ReadyNode headNode = new ReadyNode(
                "tenant-1", 1L, "run-1", UUID.randomUUID(), "head_dpu",
                NodePriority.HIGH, Instant.now(), Map.of(ResourceType.DPU, 1, ResourceType.AGENT, 1),
                () -> dispatched.add("head_dpu"), new CancellationToken("test")
        );

        // 队尾节点 B 只需 AGENT: 1 (NORMAL 优先级，可满足)
        ReadyNode nextNode = new ReadyNode(
                "tenant-1", 1L, "run-1", UUID.randomUUID(), "next_agent",
                NodePriority.NORMAL, Instant.now(), Map.of(ResourceType.AGENT, 1),
                () -> dispatched.add("next_agent"), new CancellationToken("test")
        );

        scheduler.enqueue(headNode, false);
        scheduler.enqueue(nextNode, false);
        scheduler.schedule();

        // 队头 DPU 不足，但不会阻塞队尾可满足的 next_agent
        assertThat(dispatched).containsExactly("next_agent");
    }

    @Test
    @DisplayName("已取消节点测试：被取消令牌标记的就绪节点不应占用资源或被派发")
    void cancelledNodesAreDiscardedWithoutAcquiringResources() {
        ResourceManager resources = new ResourceManager(Map.of(ResourceType.AGENT, 1));
        GlobalReadyScheduler scheduler = new GlobalReadyScheduler(resources, RuntimeCapacityProperties.defaults());

        AtomicBoolean ran = new AtomicBoolean(false);
        CancellationToken token = new CancellationToken("test");
        token.cancel("Cancelled before schedule");

        ReadyNode cancelledNode = new ReadyNode(
                "tenant-1", 1L, "run-1", UUID.randomUUID(), "cancelled_node",
                NodePriority.HIGH, Instant.now(), Map.of(ResourceType.AGENT, 1),
                () -> ran.set(true), token
        );

        scheduler.enqueue(cancelledNode);

        assertThat(ran.get()).isFalse();
        assertThat(resources.getAvailablePermits(ResourceType.AGENT)).isEqualTo(1);
        assertThat(scheduler.getQueuedNodeCount()).isEqualTo(0);
    }
}
