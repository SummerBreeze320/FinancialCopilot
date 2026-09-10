package com.financial.copilot.agent.core.pipeline;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 复合投研任务执行计划
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionPlan {

    /**
     * 是否为复合链式任务
     */
    private boolean isComplex;

    /**
     * 规划概要 (如: 筛选稳定医药基金 -> 评估前5名经理 -> Top2对标 -> 投资建议)
     */
    private String summary;

    /**
     * 有序执行子任务列表
     */
    @Builder.Default
    private List<SubTask> steps = new ArrayList<>();
}
