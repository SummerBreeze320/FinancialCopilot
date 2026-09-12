package com.financial.copilot.agent.core.dag.event;

import com.financial.copilot.agent.core.dag.runtime.context.CancellationToken;
import com.financial.copilot.common.event.ResearchStreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NodeEventBusTest {

    @Test
    @DisplayName("发布 DAG 节点级生命周期事件并由 Flux 流式消费")
    void testPublishAndConsumeLifecycleEvents() {
        CancellationToken token = new CancellationToken("run-sse-1");
        NodeEventBus eventBus = new NodeEventBus(token);

        List<ResearchStreamEvent> received = new ArrayList<>();
        Flux<ResearchStreamEvent> eventFlux = eventBus.flux();

        // 订阅收集事件
        eventFlux.subscribe(received::add);

        eventBus.publishGraphInitialized("run-sse-1", 1, List.of(
                new NodeEventBus.NodeDescriptor("node-1", "初筛", "SCREENING", List.of())
        ));
        eventBus.publishNodeStarted("run-sse-1", "node-1", "初筛", "SCREENING");
        eventBus.publishContentChunk("run-sse-1", "node-1", "正在评估...");
        eventBus.publishNodeCompleted("run-sse-1", "node-1", "SUCCEEDED", "初筛完成", List.of("art_1"));
        eventBus.publishRunCompleted("run-sse-1", "SUCCEEDED", 1200L);
        eventBus.complete();

        assertThat(received).hasSize(5);
        assertThat(received.get(0).getType()).isEqualTo("graph_initialized");
        assertThat(received.get(0).getRunId()).isEqualTo("run-sse-1");

        assertThat(received.get(1).getType()).isEqualTo("node_started");
        assertThat(received.get(1).getNodeId()).isEqualTo("node-1");

        assertThat(received.get(2).getType()).isEqualTo("content_chunk");
        assertThat(received.get(2).getChunk()).isEqualTo("正在评估...");

        assertThat(received.get(3).getType()).isEqualTo("node_completed");
        assertThat(received.get(3).getNodeId()).isEqualTo("node-1");
        assertThat(received.get(3).getStatus()).isEqualTo("SUCCEEDED");

        assertThat(received.get(4).getType()).isEqualTo("run_completed");
        assertThat(received.get(4).getStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("客户端 SSE 断开连接 (Flux.cancel) 触发绑定的 CancellationToken 自动级联取消")
    void testClientDisconnectCancelsToken() {
        CancellationToken token = new CancellationToken("run-sse-cancel");
        NodeEventBus eventBus = new NodeEventBus(token);

        Flux<ResearchStreamEvent> eventFlux = eventBus.flux();

        // 订阅并立即取消
        var disposable = eventFlux.subscribe();
        disposable.dispose();

        assertThat(token.isCancelled()).isTrue();
        assertThat(token.getReason()).contains("SSE Client Disconnected");
    }
}
