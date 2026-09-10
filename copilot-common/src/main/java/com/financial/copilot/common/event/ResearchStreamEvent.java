package com.financial.copilot.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 投研流式交互事件消息体
 * 用于 Spring WebFlux SSE 实时推送各阶段状态与打字机输出
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResearchStreamEvent {

    /**
     * 事件类型: PLAN, STEP_START, STEP_COMPLETE, CONTENT, DONE, ERROR
     */
    private String type;

    /**
     * 步骤序号 (1-indexed)
     */
    private Integer stepIndex;

    /**
     * 总步骤数
     */
    private Integer totalSteps;

    /**
     * 任务类型: SCREENING, BATCH_ANALYSIS, COMPARISON, SYNTHESIS
     */
    private String taskType;

    /**
     * 阶段提示标题 (如: 步骤 1/4: 正在进行医药基金量化初筛...)
     */
    private String title;

    /**
     * 阶段完成产出摘要 (如: 初筛完成，命中 10 只标的)
     */
    private String summary;

    /**
     * 研报打字机流式增量文本
     */
    private String chunk;

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
}
