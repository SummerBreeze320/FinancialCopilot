package com.financial.copilot.agent.core.dag.event;

import com.financial.copilot.agent.core.dag.runtime.context.CancellationToken;
import com.financial.copilot.common.event.ResearchStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Objects;

/**
 * <h1>DAG 节点级响应式事件总线 (NodeEventBus)</h1>
 * 将内部执行事件转换为 WebFlux 响应式 Flux&lt;ResearchStreamEvent&gt;，并挂载客户端连接断开中断钩子。
 */
public class NodeEventBus {

    private static final Logger log = LoggerFactory.getLogger(NodeEventBus.class);

    private final Sinks.Many<ResearchStreamEvent> sink;
    private final CancellationToken cancellationToken;

    public record NodeDescriptor(
            String nodeId,
            String name,
            String taskType,
            List<String> parentIds
    ) {}

    public NodeEventBus(CancellationToken cancellationToken) {
        this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        this.sink = Sinks.many().multicast().onBackpressureBuffer(256);
    }

    /**
     * 获取响应式事件流，挂载客户端断连 cancel 钩子
     */
    public Flux<ResearchStreamEvent> flux() {
        return sink.asFlux()
                .doOnCancel(() -> {
                    log.info("SSE Client disconnected, cancelling execution scope [{}]", cancellationToken.getScopeId());
                    cancellationToken.cancel("SSE Client Disconnected");
                });
    }

    public void emit(ResearchStreamEvent event) {
        if (event != null) {
            sink.tryEmitNext(event);
        }
    }

    public void publishGraphInitialized(String runId, int revision, List<NodeDescriptor> nodes) {
        emit(ResearchStreamEvent.graphInitialized(runId, revision, nodes));
    }

    public void publishNodeStarted(String runId, String nodeId, String nodeName, String taskType) {
        emit(ResearchStreamEvent.nodeStarted(runId, nodeId, nodeName, taskType));
    }

    public void publishNodeCompleted(String runId, String nodeId, String status, String summary, List<String> artifactIds) {
        emit(ResearchStreamEvent.nodeCompleted(runId, nodeId, status, summary, artifactIds));
    }

    public void publishGraphUpdated(String runId, int revision, Object patch) {
        emit(ResearchStreamEvent.graphUpdated(runId, revision, patch));
    }

    public void publishContentChunk(String runId, String nodeId, String chunk) {
        emit(ResearchStreamEvent.contentChunk(runId, nodeId, chunk));
    }

    public void publishRunCompleted(String runId, String status, Long durationMs) {
        emit(ResearchStreamEvent.runCompleted(runId, status, durationMs));
    }

    public void complete() {
        sink.tryEmitComplete();
    }

    public void error(Throwable error) {
        sink.tryEmitError(error);
    }
}
