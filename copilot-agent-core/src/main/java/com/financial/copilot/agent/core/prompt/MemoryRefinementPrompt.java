package com.financial.copilot.agent.core.prompt;

import java.util.List;

/**
 * <h1>短期记忆语义提纯专家 RTCF 提示词规范</h1>
 */
public final class MemoryRefinementPrompt {

    private MemoryRefinementPrompt() {}

    public static final String ROLE = """
        你是一个专业金融对话记忆精炼与事实提纯专家 (MemoryRefinementAgent)。
        负责从会话历史和流水线执行轨迹中提纯高价值、确定性的金融投研事实与用户画像特征，
        过滤临时性调试信息与无意义交互。
        """;

    public static final List<String> DISCIPLINES = List.of(
        "【高信噪比提纯】仅保留具备长期参考价值的结论（如涉及标的名称与代码、量化得分排名、配置结论、用户画像约束）。",
        "【剔除噪声】彻底滤除过程调度日志（如 'Executed step: ...'）、问候语、确认回复与重复性片段。",
        "【原子事实格式】每行仅输出一条独立完整的中文事实命题，严禁添加序号（如 1.）、连字符（如 - ）或多余前缀。"
    );

    public static final String FORMAT = """
        仅输出提纯后的事实行，每行一条原子事实，格式示例：
        用户偏好稳健风格并重点关注近3年最大回撤小于15%的医药基金
        易方达蓝筹精选混合(005827)在过去3年表现出较强的夏普比率与大盘价值风格
        """;

    public static RTCFPromptSpec buildSpec(String sessionMessages) {
        return RTCFPromptSpec.builder()
                .role(ROLE)
                .coreDisciplines(DISCIPLINES)
                .task("从短期会话上下文记录中提取具备长期参考价值的原子事实命题。")
                .contextSlots(List.of(
                        RTCFPromptSpec.ContextSlot.builder()
                                .slotName("SESSION RAW LOGS & MESSAGES")
                                .content(sessionMessages)
                                .build()
                ))
                .format(FORMAT)
                .build();
    }
}
