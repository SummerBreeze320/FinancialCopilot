package com.financial.copilot.agent.core.prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * <h1>基金横向对标与归因分析 RTCF 提示词规范</h1>
 */
public final class FundComparatorPrompt {

    private FundComparatorPrompt() {}

    public static final String ROLE = """
        你是一个资深公募基金横向对标与业绩归因分析专家 (FundComparatorAgent)。
        基于双标的对称的客观量化时序指标、真实持仓穿透和最新季报定性观点，
        撰写具有买方机构专业深度的横向对比与归因研报。
        """;

    public static final List<String> DISCIPLINES = List.of(
        "【Tool-as-Truth 铁律】严格基于输入事实上下文中的指标，绝不虚构任何收益率、回撤或持仓数据。",
        "【客观对称性】对标双方评价维度必须高度对称，避免单方面偏颇或主观臆测。",
        "【言行一致性研判】重点考察基金经理公开表态与实际重仓调仓行为的吻合度。"
    );

    public static final String FORMAT = """
        输出格式遵循专业金融研报标准 Markdown：
        ## 1. 风险-收益特征矩阵对照
        必须使用标准 Markdown 表格横向对照关键指标（近1年/3年年化、最大回撤、夏普比率、卡玛比率、持仓集中度）。
        
        ## 2. 资产配置与行业风格差异
        对比前十大重仓股重合度、前三大行业暴露及大盘成长/价值风格定位。
        
        ## 3. 投资哲学与言行一致性评估
        对照季度报告定性展望与实际持仓调仓运作，评估基金经理言行一致性。
        
        ## 4. 市场环境适应性与配置建议
        综合优劣势，分析在不同市场风格（震荡、牛市成长、防御防御）下的适应性。
        """;

    public static RTCFPromptSpec buildSpec(String codeA, String sanitizedDataA,
                                          String codeB, String sanitizedDataB,
                                          String skillInstructions) {
        return buildSpec(codeA, sanitizedDataA, codeB, sanitizedDataB, skillInstructions, null);
    }

    public static RTCFPromptSpec buildSpec(String codeA, String sanitizedDataA,
                                          String codeB, String sanitizedDataB,
                                          String skillInstructions,
                                          String graphOverlapFacts) {
        List<RTCFPromptSpec.ContextSlot> slots = new ArrayList<>();

        if (skillInstructions != null && !skillInstructions.isBlank()) {
            slots.add(RTCFPromptSpec.ContextSlot.builder()
                    .slotName("OPERATIONAL SKILL RULES")
                    .content(skillInstructions)
                    .build());
        }

        slots.add(RTCFPromptSpec.ContextSlot.builder()
                .slotName("TARGET A (" + codeA + ") FACTUAL DATA")
                .content(sanitizedDataA)
                .build());

        slots.add(RTCFPromptSpec.ContextSlot.builder()
                .slotName("TARGET B (" + codeB + ") FACTUAL DATA")
                .content(sanitizedDataB)
                .build());

        if (graphOverlapFacts != null && !graphOverlapFacts.isBlank()) {
            slots.add(RTCFPromptSpec.ContextSlot.builder()
                    .slotName("KNOWLEDGE GRAPH OVERLAP PENETRATION")
                    .content(graphOverlapFacts)
                    .build());
        }

        return RTCFPromptSpec.builder()
                .role(ROLE)
                .coreDisciplines(DISCIPLINES)
                .task("对标基金 " + codeA + " 与 " + codeB + "，执行客观对称的风险收益归因、风格差异及言行一致性分析。")
                .contextSlots(slots)
                .format(FORMAT)
                .build();
    }
}
