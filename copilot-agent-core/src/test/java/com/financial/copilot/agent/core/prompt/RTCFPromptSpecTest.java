package com.financial.copilot.agent.core.prompt;

import com.financial.copilot.agent.core.llm.dto.LlmRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RTCFPromptSpecTest {

    @Test
    @DisplayName("测试 RTCF 提示词规范分离 System 与 User 上下文")
    void testRTCFRendering() {
        RTCFPromptSpec spec = RTCFPromptSpec.builder()
                .role("金融量化分析师")
                .coreDisciplines(List.of("严禁幻觉", "数据严格溯源"))
                .task("计算标的夏普比率并生成评价")
                .contextSlots(List.of(
                        RTCFPromptSpec.ContextSlot.builder().slotName("METRICS").content("Sharpe=1.85, Drawdown=12%").build(),
                        RTCFPromptSpec.ContextSlot.builder().slotName("PROFILE").content("风险承受能力: 进取型").build()
                ))
                .format("使用 Markdown 表格输出")
                .build();

        String systemPrompt = spec.renderSystemPrompt();
        assertTrue(systemPrompt.contains("【角色定位】"));
        assertTrue(systemPrompt.contains("金融量化分析师"));
        assertTrue(systemPrompt.contains("1. 严禁幻觉"));
        assertTrue(systemPrompt.contains("2. 数据严格溯源"));
        assertFalse(systemPrompt.contains("使用 Markdown 表格输出")); // 格式不污染系统指令

        String userPrompt = spec.renderUserPrompt();
        assertTrue(userPrompt.contains("【当前任务 (Task)】"));
        assertTrue(userPrompt.contains("计算标的夏普比率并生成评价"));
        assertTrue(userPrompt.contains("### [METRICS]"));
        assertTrue(userPrompt.contains("Sharpe=1.85, Drawdown=12%"));
        assertTrue(userPrompt.contains("### [PROFILE]"));
        assertTrue(userPrompt.contains("风险承受能力: 进取型"));
        assertTrue(userPrompt.contains("【输出格式与约束 (Format)】"));
        assertTrue(userPrompt.contains("使用 Markdown 表格输出"));

        LlmRequest req = spec.toLlmRequest();
        assertEquals(systemPrompt, req.getSystemPrompt());
        assertEquals(userPrompt, req.getUserPrompt());
    }

    @Test
    @DisplayName("测试 MemoryRefinementPrompt 生成规范")
    void testMemoryRefinementPrompt() {
        RTCFPromptSpec spec = MemoryRefinementPrompt.buildSpec("User: 我要看易方达蓝筹\nAgent: 005827近三年年化12%");
        String sys = spec.renderSystemPrompt();
        String user = spec.renderUserPrompt();

        assertTrue(sys.contains("MemoryRefinementAgent"));
        assertTrue(user.contains("### [SESSION RAW LOGS & MESSAGES]"));
        assertTrue(user.contains("易方达蓝筹"));
    }

    @Test
    @DisplayName("测试 FundComparatorPrompt RTCF 规范与静态前缀对齐")
    void testFundComparatorPrompt() {
        String sysPrompt = FundComparatorPrompt.SYSTEM_PROMPT;
        assertTrue(sysPrompt.contains("FundComparatorAgent"));
        assertTrue(sysPrompt.contains("【对称对标原则】"));
        assertTrue(sysPrompt.contains("【Tool-as-Truth】"));

        RTCFPromptSpec spec = FundComparatorPrompt.buildSpec(
                "对比这两只医疗基金", List.of("003095.OF", "005827.OF"), null);
        String userPrompt = spec.renderUserPrompt();

        assertTrue(userPrompt.contains("【当前任务 (Task)】"));
        assertTrue(userPrompt.contains("003095.OF"));
        assertTrue(userPrompt.contains("### [USER GOAL]"));
        assertTrue(userPrompt.contains("对比这两只医疗基金"));
        assertTrue(userPrompt.contains("### [TARGET CODES]"));
        assertTrue(userPrompt.contains("【输出格式与约束 (Format)】"));
    }

    @Test
    @DisplayName("测试 ReportSynthesizerPrompt RTCF 规范与静态前缀对齐")
    void testReportSynthesizerPrompt() {
        String sysPrompt = ReportSynthesizerPrompt.SYSTEM_PROMPT;
        assertTrue(sysPrompt.contains("ReportSynthesizerAgent"));
        assertTrue(sysPrompt.contains("【证据强闭环约束】"));
        assertTrue(sysPrompt.contains("read_research_artifacts"));

        RTCFPromptSpec spec = ReportSynthesizerPrompt.buildSpec("生成终审报告", null);
        String userPrompt = spec.renderUserPrompt();

        assertTrue(userPrompt.contains("【当前任务 (Task)】"));
        assertTrue(userPrompt.contains("### [USER GOAL]"));
        assertTrue(userPrompt.contains("生成终审报告"));
        assertTrue(userPrompt.contains("【输出格式与约束 (Format)】"));
    }

    @Test
    @DisplayName("测试 GraphPlannerPrompt RTCF 规范与静态前缀对齐")
    void testGraphPlannerPrompt() {
        String planSys = GraphPlannerPrompt.PLAN_SYSTEM_PROMPT;
        assertTrue(planSys.contains("GraphPlannerAgent"));
        assertTrue(planSys.contains("【能力按需发现】"));
        assertTrue(planSys.contains("【拓扑无环契约】"));

        String patchSys = GraphPlannerPrompt.PATCH_SYSTEM_PROMPT;
        assertTrue(patchSys.contains("GraphPatchAgent"));
        assertTrue(patchSys.contains("NO_PATCH"));

        RTCFPromptSpec planSpec = GraphPlannerPrompt.buildPlanSpec("筛选消费基金", "sess-123", null);
        String userPrompt = planSpec.renderUserPrompt();

        assertTrue(userPrompt.contains("【当前任务 (Task)】"));
        assertTrue(userPrompt.contains("### [USER GOAL]"));
        assertTrue(userPrompt.contains("筛选消费基金"));
        assertTrue(userPrompt.contains("### [SESSION KEY]"));
        assertTrue(userPrompt.contains("sess-123"));
        assertTrue(userPrompt.contains("GraphPlan"));
    }

    @Test
    @DisplayName("测试 GraphPlannerPrompt 成功装配多轮短期上下文槽位")
    void testGraphPlannerPromptWithRecentContext() {
        List<String> recentContext = List.of(
                "USER: 筛选两只医药基金",
                "ASSISTANT: 推荐工银医疗(003095)与中欧医疗(005827)"
        );
        RTCFPromptSpec planSpec = GraphPlannerPrompt.buildPlanSpec(
                "对比刚才那两只的重仓股", "sess-multi", null, recentContext);
        String userPrompt = planSpec.renderUserPrompt();

        assertTrue(userPrompt.contains("### [CONVERSATION RECENT CONTEXT]"));
        assertTrue(userPrompt.contains("003095"));
        assertTrue(userPrompt.contains("005827"));
        assertTrue(userPrompt.contains("CONVERSATION RECENT CONTEXT 解析对应标的"));
    }
}
