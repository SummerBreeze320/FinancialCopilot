package com.financial.copilot.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * <h1>投研流式交互事件消息体 (ResearchStreamEvent)</h1>
 * 用于 Spring WebFlux SSE 实时推送各阶段状态、DAG 拓扑生命周期与打字机输出。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResearchStreamEvent {

    /**
     * 事件类型:
     * 传统: PLAN, STEP_START, STEP_COMPLETE, CONTENT, DONE, ERROR
     * DAG增强: graph_initialized, node_started, node_completed, graph_updated, content_chunk, run_completed
     */
    private String type;

    // --- 传统字段保持 100% 向下兼容 ---
    private Integer stepIndex;
    private Integer totalSteps;
    private String taskType;
    private String title;
    private String summary;
    private String chunk;

    // --- DAG 节点级流式拓扑增强字段 ---
    private String runId;
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

    // 传统工厂方法
    public static ResearchStreamEvent plan(int totalSteps, String summary) {
        return ResearchStreamEvent.builder()
                .type("PLAN")
                .totalSteps(totalSteps)
                .summary(summary)
                .build();
    }

    public static ResearchStreamEvent stepStart(int stepIndex, int totalSteps, String taskType, String title) {
        return ResearchStreamEvent.builder()
                .type("STEP_START")
                .stepIndex(stepIndex)
                .totalSteps(totalSteps)
                .taskType(taskType)
                .title(title)
                .build();
    }

    public static ResearchStreamEvent stepComplete(int stepIndex, int totalSteps, String taskType, String summary) {
        return ResearchStreamEvent.builder()
                .type("STEP_COMPLETE")
                .stepIndex(stepIndex)
                .totalSteps(totalSteps)
                .taskType(taskType)
                .summary(summary)
                .build();
    }

    public static ResearchStreamEvent content(String chunk) {
        return ResearchStreamEvent.builder()
                .type("CONTENT")
                .chunk(chunk)
                .build();
    }

    public static ResearchStreamEvent done() {
        return ResearchStreamEvent.builder()
                .type("DONE")
                .build();
    }

    // --- DAG 增强工厂方法 ---
    public static ResearchStreamEvent graphInitialized(String runId, int revision, Object nodes) {
        return ResearchStreamEvent.builder()
                .type("graph_initialized")
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
}
