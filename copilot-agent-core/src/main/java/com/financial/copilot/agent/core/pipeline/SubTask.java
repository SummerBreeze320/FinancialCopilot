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
 * <h1>复合投研流水线子任务单元</h1>
 * <p>
 * 定义流水线中每个独立作业步骤的元信息，包括阶段序号、任务类别、依赖的前置步骤、输入输出黑板键值及专有计算参数。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubTask {

    /**
     * 步骤序号 (从 1 开始递增)
     */
    private int stepIndex;

    /**
     * 任务类型枚举值：
     * <ul>
     *   <li>SCREENING: 资产多维量化筛选</li>
     *   <li>BATCH_ANALYSIS: 候选标的/基金经理多维批量体检打分</li>
     *   <li>COMPARISON: 决赛圈对称量化指标与季报定性观点对标</li>
     *   <li>SYNTHESIS: 投研主编最终综合报告生成</li>
     * </ul>
     */
    private String taskType;

    /**
     * 该步骤的自然语言目标描述
     */
    private String description;

    /**
     * 依赖的前置步骤序号列表 (例如 [1] 表示必须等待步骤 1 完成)
     */
    @Builder.Default
    private List<Integer> dependsOn = new ArrayList<>();

    /**
     * 从黑板中提取输入数据的键名 (可选)
     */
    private String inputKey;

    /**
     * 将本步骤执行产出写入黑板的目标键名
     */
    private String outputKey;

    /**
     * 该步骤的结构化执行参数集 (例如: sector="医药", topN=5, selectBest=2)
     */
    @Builder.Default
    private Map<String, Object> params = new HashMap<>();
}
