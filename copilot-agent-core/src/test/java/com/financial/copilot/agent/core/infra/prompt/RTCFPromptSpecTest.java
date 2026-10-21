package com.financial.copilot.agent.core.infra.prompt;

import com.financial.copilot.gateway.model.LlmRequest;
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
    @DisplayName("测试 SupervisorPrompts RTCF 规范与静态系统提示词前缀")
    void testSupervisorPrompts() {
        String sysPrompt = SupervisorPrompts.SYSTEM_PROMPT;
        assertTrue(sysPrompt.contains("research_supervisor"));
        assertTrue(sysPrompt.contains("【精准分工委派】"));
        assertTrue(sysPrompt.contains("【Tool-as-Truth 客观事实】"));
        assertTrue(sysPrompt.contains("【风控合规前置】"));
        assertTrue(sysPrompt.contains("【复杂研报合成】"));
    }

    @Test
    @DisplayName("测试 SubagentPrompts 5大专业子智能体系统提示词渲染")
    void testSubagentPrompts() {
        // 1. 基金智能体
        String fundPrompt = SubagentPrompts.FUND_AGENT_SYSTEM_PROMPT;
        assertTrue(fundPrompt.contains("fund_research_agent"));
        assertTrue(fundPrompt.contains("Tool-as-Truth"));

        // 2. 股票智能体
        String stockPrompt = SubagentPrompts.STOCK_AGENT_SYSTEM_PROMPT;
        assertTrue(stockPrompt.contains("stock_research_agent"));
        assertTrue(stockPrompt.contains("真实财报锚定"));

        // 3. 市场智能体
        String marketPrompt = SubagentPrompts.MARKET_AGENT_SYSTEM_PROMPT;
        assertTrue(marketPrompt.contains("market_research_agent"));
        assertTrue(marketPrompt.contains("权威指标释义"));

        // 4. 风控审查智能体
        String riskPrompt = SubagentPrompts.RISK_REVIEW_SYSTEM_PROMPT;
        assertTrue(riskPrompt.contains("risk_review_agent"));
        assertTrue(riskPrompt.contains("只读证据链核验"));
        assertTrue(riskPrompt.contains("适格性穿透审查"));

        // 5. 研报合成智能体
        String reportPrompt = SubagentPrompts.REPORT_AGENT_SYSTEM_PROMPT;
        assertTrue(reportPrompt.contains("report_agent"));
        assertTrue(reportPrompt.contains("全量证据闭环"));
        assertTrue(reportPrompt.contains("机构级研报架构"));
    }
}
