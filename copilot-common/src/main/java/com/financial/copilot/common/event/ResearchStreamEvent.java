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
    private Long sequenceNo;

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

    public static ResearchStreamEvent runCompleted(String runId, String status, Long durationMs) {
        return ResearchStreamEvent.builder()
                .type("run_completed")
                .runId(runId)
                .status(status)
                .durationMs(durationMs)
                .build();
    }

    public static ResearchStreamEvent runQueued(String runId, String conversationId, String reason, Integer queuePosition) {
        return ResearchStreamEvent.builder()
                .type("run_queued")
                .runId(runId)
                .conversationId(conversationId)
                .status("QUEUED")
                .summary(reason)
                .payload(queuePosition != null ? java.util.Map.of("queuePosition", queuePosition) : null)
                .build();
    }

    public static ResearchStreamEvent runAdmitted(String runId, String conversationId) {
        return ResearchStreamEvent.builder()
                .type("run_admitted")
                .runId(runId)
                .conversationId(conversationId)
                .status("ADMITTED")
                .build();
    }

    public static ResearchStreamEvent runStarted(String runId, String conversationId) {
        return ResearchStreamEvent.builder()
                .type("run_started")
                .runId(runId)
                .conversationId(conversationId)
                .status("RUNNING")
                .build();
    }

    public static ResearchStreamEvent reviewStarted(String runId, int cycle, int maxCycles) {
        return ResearchStreamEvent.builder()
                .type("review_started")
                .runId(runId)
                .payload(java.util.Map.of("cycle", cycle, "maxCycles", maxCycles))
                .build();
    }

    public static ResearchStreamEvent reviewDecision(String runId, int cycle, String action, List<String> targetTaskIds, List<String> issues, double confidence) {
        return ResearchStreamEvent.builder()
                .type("review_decision")
                .runId(runId)
                .payload(java.util.Map.of(
                        "cycle", cycle,
                        "action", action,
                        "targetTaskIds", targetTaskIds != null ? targetTaskIds : List.of(),
                        "issues", issues != null ? issues : List.of(),
                        "confidence", confidence
                ))
                .build();
    }

    public static ResearchStreamEvent runDegraded(String runId, String reason, List<String> issues) {
        return ResearchStreamEvent.builder()
                .type("run_degraded")
                .runId(runId)
                .status("DEGRADED")
                .summary(reason)
                .payload(java.util.Map.of("issues", issues != null ? issues : List.of()))
                .build();
    }

    public static ResearchStreamEvent replanTriggered(String runId, int cycle, String reason) {
        return ResearchStreamEvent.builder()
                .type("replan_triggered")
                .runId(runId)
                .summary(reason)
                .payload(java.util.Map.of("cycle", cycle))
                .build();
    }
}
