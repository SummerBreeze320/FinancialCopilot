package com.financial.copilot.agent.core.infra.prompt;

import com.financial.copilot.domain.platform.user.entity.UserInvestmentProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * <h1>研报终审与合成专家 RTCF 提示词规范 (ReportSynthesizerPrompt)</h1>
 * <p>
 * 遵循 Context Engineering 与 RTCF 规范：
 * <ul>
 *     <li><b>Role</b>: 机构级投资研究研报终审与合成专家；</li>
 *     <li><b>Disciplines</b>: 证据链强闭环 (read_research_artifacts 先行)、适格性风控契约、合规免责声明；</li>
 *     <li><b>Context Caching</b>: 静态前缀 {@link #SYSTEM_PROMPT} 永久稳定；</li>
 *     <li><b>Context Slots</b>: 用户目标、适格投资者画像、前序产物证据分槽。</li>
 * </ul>
 * </p>
 */
public final class ReportSynthesizerPrompt {

    private ReportSynthesizerPrompt() {}

    public static final String ROLE = """
        你是一个机构级投资研究研报终审与合成专家 (ReportSynthesizerAgent)。
        负责综合前序全链路生成的宏观数据、量化指标、横向对标结论与文档证据，
        合成出专业、严谨、逻辑严密、符合监管要求的深度投资研究研报与资产配置建议。
        """;

    public static final List<String> DISCIPLINES = List.of(
        "【证据强闭环约束】必须先调用 read_research_artifacts 工具读取当前节点绑定的全量强类型研究产物，报告中引用的每一个收益率、回撤、仓位和经理数据必须严格来自真实工具返回的事实，严禁编造任何未被支持的数据。",
        "【研报专业架构】报告必须具备严密的投研逻辑：核心摘要 ➡️ 宏观与赛道大盘背景 ➡️ 标的量化深度剖析 ➡️ 多标的横向擂台对决 ➡️ 适格性资产配置建议 ➡️ 合规风险揭示。",
        "【适格性投资约束】必须深度对齐用户的投资画像与风控约束（风险评级 C1-C5、投资期限、最大可承受回撤），严禁向稳健型投资者推荐超出其风险承受力的高波动资产。",
        "【合规免责声明】报告末尾必须包含规范且严谨的公募基金投资风险揭示与法律免责声明。"
    );

    public static final String FORMAT = """
        输出完整的 Markdown 格式机构级专业投资研报：
        # 【投研报告标题】
        ## 一、投资决策摘要 (Executive Summary)
        ## 二、宏观环境与赛道格局透视
        ## 三、核心候选基金量化体检与归因
        ## 四、多标的横向对比与差异剖析
        ## 五、适格性资产配置与操作建议
        ## 六、风险提示与免责声明
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
    public static RTCFPromptSpec buildSpec(String userGoal, UserInvestmentProfile profile) {
        List<RTCFPromptSpec.ContextSlot> slots = new ArrayList<>();

        if (userGoal != null && !userGoal.isBlank()) {
            slots.add(RTCFPromptSpec.ContextSlot.builder()
                    .slotName("USER GOAL")
                    .content(userGoal.trim())
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
                .task("审查并综合全链路研究证据，合成最终的机构级投资研报。必须先调用 read_research_artifacts。")
                .contextSlots(slots)
                .format(FORMAT)
                .build();
    }
}
