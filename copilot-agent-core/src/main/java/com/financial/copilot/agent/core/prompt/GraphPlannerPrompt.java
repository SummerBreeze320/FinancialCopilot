package com.financial.copilot.agent.core.prompt;

import com.financial.copilot.domain.user.entity.UserInvestmentProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * <h1>动态图规划与复杂任务解构专家 RTCF 提示词规范 (GraphPlannerPrompt)</h1>
 * <p>
 * 遵循 Context Engineering 与 RTCF 规范：
 * <ul>
 *     <li><b>Role</b>: 动态图规划与复杂投研意图解构专家；</li>
 *     <li><b>Disciplines</b>: 能力探查先行、拓扑无环与强类型产物依赖绑定；</li>
 *     <li><b>Context Caching</b>: 静态系统提示词前缀固定；</li>
 *     <li><b>Context Slots</b>: 用户目标、适格画像、会话记忆分槽隔离。</li>
 * </ul>
 * </p>
 */
public final class GraphPlannerPrompt {

    private GraphPlannerPrompt() {}

    public static final String PLAN_ROLE = """
        你是动态图规划与任务解构 ReAct Agent (GraphPlannerAgent)。
        负责根据用户的自然语言复合投研诉求与画像，解构并编排为无环、强类型安全、依赖明确的 DAG 动态执行图。
        """;

    public static final List<String> PLAN_DISCIPLINES = List.of(
        "【能力按需发现】必须先按需调用 search_metrics、list_skills、check_capability、search_documents、read_memory 工具探测中台算子能力与业务准则，在完成必要的能力发现前禁止结束。",
        "【拓扑无环契约】编排的图必须是有向无环图 (DAG)，严禁循环依赖；每个非首节点必须通过 inputBindings 显式绑定上游节点产物。",
        "【强类型产物约定】每个业务节点必须声明 taskType、outputType、inputBindings、failurePolicy、资源和超时。允许的业务 taskType：SCREENING、BATCH_ANALYSIS、COMPARISON、DEEP_DIVE、SYNTHESIS。公募基金链路 outputType 依次对应 FUND_POOL、FUND_RESEARCH、COMPARISON_REPORT、FINAL_REPORT。",
        "【严格 JSON 契约】最终响应必须直接输出合法的 GraphPlan JSON，严禁掺杂解释性废话。"
    );

    public static final String PATCH_ROLE = """
        你是动态图重规划 ReAct Agent (GraphPatchAgent)。
        负责检查当前执行图的运行状态、已完成节点产物与异常失败，在必要时生成局部重规划补丁 GraphPatch。
        """;

    public static final List<String> PATCH_DISCIPLINES = List.of(
        "【审慎修改契约】仅在下游节点无法完成目标或上游产生空结果时触发局部拓扑修改；无需修改时最终只输出 NO_PATCH。",
        "【严格 JSON 契约】若需修改，最终响应提交严格的 GraphPatch JSON，严禁输出无效标记。"
    );

    /**
     * 静态初次建图系统提示词，保持前缀稳定
     */
    public static final String PLAN_SYSTEM_PROMPT = renderStaticPlanSystemPrompt();

    /**
     * 静态动态重规划系统提示词，保持前缀稳定
     */
    public static final String PATCH_SYSTEM_PROMPT = renderStaticPatchSystemPrompt();

    private static String renderStaticPlanSystemPrompt() {
        return RTCFPromptSpec.builder()
                .role(PLAN_ROLE)
                .coreDisciplines(PLAN_DISCIPLINES)
                .build()
                .renderSystemPrompt();
    }

    private static String renderStaticPatchSystemPrompt() {
        return RTCFPromptSpec.builder()
                .role(PATCH_ROLE)
                .coreDisciplines(PATCH_DISCIPLINES)
                .build()
                .renderSystemPrompt();
    }

    /**
     * 构建初始建图用户提示词规格
     */
    public static RTCFPromptSpec buildPlanSpec(String userGoal, String sessionKey, UserInvestmentProfile profile) {
        List<RTCFPromptSpec.ContextSlot> slots = new ArrayList<>();

        if (userGoal != null && !userGoal.isBlank()) {
            slots.add(RTCFPromptSpec.ContextSlot.builder()
                    .slotName("USER GOAL")
                    .content(userGoal.trim())
                    .build());
        }

        if (sessionKey != null && !sessionKey.isBlank()) {
            slots.add(RTCFPromptSpec.ContextSlot.builder()
                    .slotName("SESSION KEY")
                    .content(sessionKey.trim())
                    .build());
        }

        if (profile != null) {
            slots.add(RTCFPromptSpec.ContextSlot.builder()
                    .slotName("INVESTOR PROFILE")
                    .content(profile.toAgentPromptSummary())
                    .build());
        }

        return RTCFPromptSpec.builder()
                .role(PLAN_ROLE)
                .coreDisciplines(PLAN_DISCIPLINES)
                .task("根据用户诉求与画像，先调用能力发现工具探测算子，然后输出完整的 GraphPlan JSON 拓扑图。")
                .contextSlots(slots)
                .format("输出无任何 Markdown 标记的纯 JSON 格式 GraphPlan 对象。")
                .build();
    }
}
