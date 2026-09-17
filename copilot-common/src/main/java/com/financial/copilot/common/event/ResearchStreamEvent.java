package com.financial.copilot.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * <h1>投研流式交互事件消息体 (ResearchStreamEvent)</h1>
 * 用于 Spring WebFlux SSE 实时推送执行图生命周期与研报增量输出。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResearchStreamEvent {

    /**
     * 事件类型: graph_initialized、node_ready、node_started、node_completed、graph_updated、
     * content_chunk、node_failed、run_failed、run_cancelled、run_completed。
     */
    private String type;

    private String taskType;
    private String title;
    private String summary;
    private String chunk;

    private String runId;
    private String conversationId;
    private String nodeId;
    private String nodeName;
    private String status;
    private Integer revision;
    private Object nodes;
    private List<String> artifactIds;
    private Object components;
    private Long durationMs;
    private Object payload;
    private Object patch;

    public static ResearchStreamEvent graphInitialized(String runId, String conversationId, int revision, Object nodes) {
        return ResearchStreamEvent.builder()
                .type("graph_initialized").conversationId(conversationId)
                .runId(runId)
                .revision(revision)
                .nodes(nodes)
                .build();
    }

    public static ResearchStreamEvent nodeStarted(String runId, String nodeId, String nodeName, String taskType) {
        return ResearchStreamEvent.builder()
                .type("node_started")
                .runId(runId)
                .nodeId(nodeId)
                .nodeName(nodeName)
                .taskType(taskType)
                .status("RUNNING")
                .build();
    }

    public static ResearchStreamEvent nodeReady(String runId, String nodeId, String nodeName, String taskType) {
        return ResearchStreamEvent.builder().type("node_ready").runId(runId).nodeId(nodeId)
                .nodeName(nodeName).taskType(taskType).status("READY").build();
    }

    public static ResearchStreamEvent nodeFailed(String runId, String nodeId, String status, String summary) {
        return ResearchStreamEvent.builder().type("node_failed").runId(runId).nodeId(nodeId)
                .status(status).summary(summary).build();
    }

    public static ResearchStreamEvent runFailed(String runId, String summary) {
        return ResearchStreamEvent.builder().type("run_failed").runId(runId).status("FAILED").summary(summary).build();
    }

    public static ResearchStreamEvent runCancelled(String runId, String summary) {
        return ResearchStreamEvent.builder().type("run_cancelled").runId(runId).status("CANCELLED").summary(summary).build();
    }

    public static ResearchStreamEvent nodeCompleted(String runId, String nodeId, String status, String summary, List<String> artifactIds) {
        return ResearchStreamEvent.builder()
                .type("node_completed")
                .runId(runId)
                .nodeId(nodeId)
                .status(status)
                .summary(summary)
                .artifactIds(artifactIds)
                .build();
    }

    public static ResearchStreamEvent graphUpdated(String runId, int revision, Object patch) {
        return ResearchStreamEvent.builder()
                .type("graph_updated")
                .runId(runId)
                .revision(revision)
                .patch(patch)
                .build();
    }

    public static ResearchStreamEvent contentChunk(String runId, String nodeId, String chunk) {
        return ResearchStreamEvent.builder()
                .type("content_chunk")
                .runId(runId)
                .nodeId(nodeId)
                .chunk(chunk)
                .build();
    }

    public static ResearchStreamEvent workspaceUpdated(String runId, String nodeId, Object workspace) {
        return ResearchStreamEvent.builder()
                .type("workspace")
                .runId(runId)
                .nodeId(nodeId)
                .payload(workspace)
                .components(workspace)
                .build();
    }

    public static ResearchStreamEvent runCompleted(String runId, String status, Long durationMs) {
        return ResearchStreamEvent.builder()
                .type("run_completed")
                .runId(runId)
                .status(status)
                .durationMs(durationMs)
                .build();
    }
}
