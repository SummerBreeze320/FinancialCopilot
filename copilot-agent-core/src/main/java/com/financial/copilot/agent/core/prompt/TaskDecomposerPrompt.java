package com.financial.copilot.agent.core.prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * <h1>投研任务解构专家 RTCF 提示词规范</h1>
 */
public final class TaskDecomposerPrompt {

    private TaskDecomposerPrompt() {}

    public static final String ROLE = """
        你是一个资深金融智能投研规划专家。
        专注于深度理解用户的自然语言投资指令，精准识别涉及的资产大类（公募基金/股票等），
        并将其分解为最小可行、逻辑严密的多智能体执行计划 (ExecutionPlan)。
        """;

    public static final List<String> DISCIPLINES = List.of(
        "单意图请求（如仅筛选、仅查询单标的或仅对比指定标的）设置 isComplex 为 false，steps 仅包含 1 个步骤。",
        "复合投研流水线严格遵循阶段依赖链条：SCREENING -> BATCH_ANALYSIS -> COMPARISON -> SYNTHESIS。",
        "支持资产大类：FUND (公募基金), STOCK (股票标的), FUTURES (期货), WEALTH_MANAGEMENT (理财)。",
        "规划时如提供了用户历史偏好与既往决策事实，应参考该事实对初筛条件和步骤参数进行针对性适配。",
        "必须且仅输出合法的 JSON 格式字符串，严禁输出任何多余的前言、解释或闲聊文本。"
    );

    public static final String FORMAT = """
        必须输出严格合法的 JSON，结构格式如下：
        {
          "assetCategory": "FUND",
          "isComplex": true,
          "summary": "简述任务流水线规划概要",
          "steps": [
            {
              "stepId": 1,
              "taskType": "SCREENING",
              "description": "按板块与稳定性指标初筛公募基金",
              "dependencies": []
            },
            {
              "stepId": 2,
              "taskType": "BATCH_ANALYSIS",
              "description": "分析初筛候选池前 5 名基金经理的能力表现",
              "dependencies": [1]
            },
            {
              "stepId": 3,
              "taskType": "COMPARISON",
              "description": "对比综合评分最优的两强基金标的",
              "dependencies": [2]
            },
            {
              "stepId": 4,
              "taskType": "SYNTHESIS",
              "description": "综合生成资产配置与投资建议研报",
              "dependencies": [3]
            }
          ]
        }
        """;

    public static RTCFPromptSpec buildSpec(String userQuery) {
        return buildSpec(userQuery, null);
    }

    public static RTCFPromptSpec buildSpec(String userQuery, List<String> historicalFacts) {
        List<RTCFPromptSpec.ContextSlot> slots = new ArrayList<>();
        slots.add(RTCFPromptSpec.ContextSlot.builder()
                .slotName("USER QUERY")
                .content(userQuery)
                .build());

        if (historicalFacts != null && !historicalFacts.isEmpty()) {
            slots.add(RTCFPromptSpec.ContextSlot.builder()
                    .slotName("USER HISTORICAL PROFILE & REFINED FACTS")
                    .content(String.join("\n", historicalFacts))
                    .build());
        }

        return RTCFPromptSpec.builder()
                .role(ROLE)
                .coreDisciplines(DISCIPLINES)
                .task("分析用户投资指令与历史画像偏好，识别资产类别与任务复杂度，生成结构化执行步骤列表。")
                .contextSlots(slots)
                .format(FORMAT)
                .build();
    }
}
