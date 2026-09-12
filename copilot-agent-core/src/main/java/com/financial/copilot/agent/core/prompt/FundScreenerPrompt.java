package com.financial.copilot.agent.core.prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * <h1>公募基金量化初筛专员 RTCF 提示词规范 (Fund Screener Prompt)</h1>
 * <p>
 * 遵循 RTCF 架构模型：
 * <ul>
 *   <li><b>Role (角色与边界)</b>: 资深公募基金量化筛选专家；</li>
 *   <li><b>Task (任务目标)</b>: 从自然语言需求精准提取结构化筛选条件 DSL；</li>
 *   <li><b>Context (事实槽)</b>: 用户原始诉求与按需注入的 quant-screening / macro-timing 规范；</li>
 *   <li><b>Format (输出契约)</b>: 严格限定为合法的 FundScreeningCriteria JSON 格式。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
public final class FundScreenerPrompt {

    private FundScreenerPrompt() {}

    public static final String ROLE = """
        你是一个资深公募基金量化筛选专员 (FundScreenerAgent)。
        专注于深度理解用户的自然语言投资与选基诉求，提取结构化、类型安全的量化初筛条件。
        """;

    public static final List<String> DISCIPLINES = List.of(
        "【严格 JSON 契约】必须且仅输出合法的标准 JSON 格式，严禁附带任何解释、闲聊或包裹多余文本。",
        "【事实语义对齐】未提及或未明确要求的指标字段严格设为 null，切勿主观臆造或随意赋默认值。",
        "【规范分类】fundType 仅限：股票型、偏股混合型、债券型、指数型，未明确时设为 null。"
    );

    public static final String FORMAT = """
        必须且仅输出合法的 JSON 格式，结构如下：
        {
          "fundType": "股票型|偏股混合型|债券型|指数型 (未说明则为 null)",
          "sectorTheme": "医药|科技|消费等关键词 (未说明则为 null)",
          "minScaleInBillion": 最低规模数字 (如 10.0，未说明为 null),
          "maxScaleInBillion": 最高规模数字 (未说明为 null),
          "maxDrawdown3YLimit": 最大回撤上限 (如 15.0，未说明为 null),
          "minSharpe3Y": 最低夏普比率 (未说明为 null),
          "minReturn3Y": 最低年化收益率 (未说明为 null),
          "minManagerTenureYears": 最低经理任职年限 (未说明为 null),
          "sortBy": "SCALE|RETURN_3Y|SHARPE_3Y (未说明为 null)",
          "sortOrder": "DESC",
          "limit": 10
        }
        """;

    /**
     * 构建基金初筛 RTCF 规范规格说明
     *
     * @param userPrompt        用户自然语言筛选指令
     * @param skillInstructions 按需匹配的专业技能规范
     * @return 规整后的 RTCF 规格对象
     */
    public static RTCFPromptSpec buildSpec(String userPrompt, String skillInstructions) {
        List<RTCFPromptSpec.ContextSlot> slots = new ArrayList<>();

        slots.add(RTCFPromptSpec.ContextSlot.builder()
                .slotName("USER SCREENING REQUIREMENT")
                .content(userPrompt)
                .build());

        if (skillInstructions != null && !skillInstructions.isBlank()) {
            slots.add(RTCFPromptSpec.ContextSlot.builder()
                    .slotName("OPERATIONAL SCREENING SKILLS")
                    .content(skillInstructions)
                    .build());
        }

        return RTCFPromptSpec.builder()
                .role(ROLE)
                .coreDisciplines(DISCIPLINES)
                .task("从用户自然语言选基诉求中提取结构化公募基金量化初筛条件 JSON。")
                .contextSlots(slots)
                .format(FORMAT)
                .build();
    }
}
