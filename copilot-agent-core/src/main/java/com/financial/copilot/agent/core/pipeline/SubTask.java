package com.financial.copilot.agent.core.pipeline;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 复合投研子任务单元
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubTask {

    /**
     * 步骤序号 (1-indexed)
     */
    private int stepIndex;

    /**
     * 任务类型 (SCREENING / BATCH_ANALYSIS / COMPARISON / SYNTHESIS)
     */
    private String taskType;

    /**
     * 任务自然语言描述与目标
     */
    private String description;

    /**
     * 依赖的前置步骤序号
     */
    @Builder.Default
    private List<Integer> dependsOn = new ArrayList<>();

    /**
     * 从黑板读取的输入数据 Key
     */
    private String inputKey;

    /**
     * 写入黑板的目标数据 Key
     */
    private String outputKey;

    /**
     * 结构化参数 (如 sector: "医药", topN: 5, selectBest: 2)
     */
    @Builder.Default
    private Map<String, Object> params = new HashMap<>();
}
