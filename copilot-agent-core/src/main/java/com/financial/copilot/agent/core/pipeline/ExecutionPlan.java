package com.financial.copilot.agent.core.pipeline;

import com.financial.copilot.common.enums.AssetCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * <h1>复合投研任务执行计划模型</h1>
 * <p>
 * 封装由 {@link TaskDecomposer} 解析出的整体流水线规划，
 * 包含资产大类属性、是否为复合依赖链、总体目标摘要及顺序执行的子任务清单。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionPlan {

    /**
     * 目标资产大类 (默认 FUND 公募基金，支持 STOCK 股票、FUTURES 期货、WEALTH 理财)
     */
    @Builder.Default
    private AssetCategory assetCategory = AssetCategory.FUND;

    /**
     * 是否为复合长链路任务 (true: 多步拓扑依赖链; false: 单意图直达)
     */
    private boolean isComplex;

    /**
     * 任务总体规划摘要 (例如: "三年稳定医药基金筛选 -> 前5名经理多维评估 -> Top2横向对标 -> 最终配置建议")
     */
    private String summary;

    /**
     * 顺序排列的子任务执行列表
     */
    @Builder.Default
    private List<SubTask> steps = new ArrayList<>();
}
