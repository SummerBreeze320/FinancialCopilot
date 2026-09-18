package com.financial.copilot.agent.core.infra.prompt;

import com.financial.copilot.domain.platform.user.entity.UserInvestmentProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * <h1>基金横向对标专家 RTCF 提示词规范 (FundComparatorPrompt)</h1>
 * <p>
 * 遵循 Context Engineering 与 RTCF 规范：
 * <ul>
 *     <li><b>Role</b>: 资深公募基金横向评测与对标专家；</li>
 *     <li><b>Disciplines</b>: 严格对称对标、Tool-as-Truth 防幻觉、持仓穿透排雷；</li>
 *     <li><b>Context Caching</b>: 静态前缀 {@link #SYSTEM_PROMPT} 保证服务商端缓存高命中率；</li>
 *     <li><b>Context Slots</b>: 用户目标、目标标的、投资者画像分槽隔离装配。</li>
 * </ul>
 * </p>
 */
public final class FundComparatorPrompt {

    private FundComparatorPrompt() {}

    public static final String ROLE = """
        你是一个资深基金横向评测与对标专家 (FundComparatorAgent)。
        负责对给定的多只候选基金执行严格、客观、对称的多维度横向对标与底层穿透对比，
        帮助投资者识别同类产品的核心差异、风险暴露与超额收益来源。
        """;

    public static final List<String> DISCIPLINES = List.of(
        "【对称对标原则】对标分析必须保证所有对比标的在指标口径、时间区间（同起止日）上严格对齐，双标的必须对称比较，严禁模型心算或非对称主观臆测。",
        "【Tool-as-Truth】必须且至少调用 compare_metrics 或相应对标工具获取客观真实数据，严禁编造未被工具观察支持的量化数值。",
        "【持仓穿透与排雷】不仅对比表层收益率，还需深入对比前十大重仓股票重合度、行业暴露与 Brinson 归因，识别是 Beta 市场行情还是 Alpha 选股超额。",
        "【客观中立结论】客观陈述各标的优势与潜在风险回撤控制能力，给出逻辑严谨的综合对比与选拔结论。"
    );

    public static final String FORMAT = """
        基于工具返回的真实观察事实，输出结构清晰的 Markdown 对标分析报告：
        1. 基础资料与费率对标（规模、净值、经理从业与管理费率）
        2. 收益表现与动态回撤对标（区间年化、最大回撤曲线对比）
        3. 底层持仓穿透与重合度排雷（重仓股票/行业重合度）
        4. 综合选拔结论（适合的配置场景与优劣势总结）
        """;

    /**
     * 静态系统提示词，保持前缀稳定，最大化复用 Context Caching 缓存
     */
    public static final String SYSTEM_PROMPT = renderStaticSystemPrompt();

    private static String renderStaticSystemPrompt() {
        return RTCFPromptSpec.builder()
                .role(ROLE)
                .coreDisciplines(DISCIPLINES)
                .build()
                .renderSystemPrompt();
    }

    /**
     * 构建动态用户提示词规格
     */
    public static RTCFPromptSpec buildSpec(String userGoal, List<String> targetCodes, UserInvestmentProfile profile) {
        List<RTCFPromptSpec.ContextSlot> slots = new ArrayList<>();

        if (userGoal != null && !userGoal.isBlank()) {
            slots.add(RTCFPromptSpec.ContextSlot.builder()
                    .slotName("USER GOAL")
                    .content(userGoal.trim())
                    .build());
        }

        if (targetCodes != null && !targetCodes.isEmpty()) {
            slots.add(RTCFPromptSpec.ContextSlot.builder()
                    .slotName("TARGET CODES")
                    .content(String.join(", ", targetCodes))
                    .build());
        }

        if (profile != null) {
            slots.add(RTCFPromptSpec.ContextSlot.builder()
                    .slotName("INVESTOR PROFILE")
                    .content(profile.toAgentPromptSummary())
                    .build());
        }

        return RTCFPromptSpec.builder()
                .role(ROLE)
                .coreDisciplines(DISCIPLINES)
                .task("对目标候选基金 " + targetCodes + " 进行对称量化对标与持仓穿透深度比较。")
                .contextSlots(slots)
                .format(FORMAT)
                .build();
    }
}
