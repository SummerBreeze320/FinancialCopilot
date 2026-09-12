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

}
