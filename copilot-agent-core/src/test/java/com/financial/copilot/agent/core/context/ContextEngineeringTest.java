package com.financial.copilot.agent.core.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.memory.ContextReducer;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;
import com.financial.copilot.domain.user.enums.RiskToleranceLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextEngineeringTest {

    private ObservationSanitizer sanitizer;
    private ContextBudgetManager budgetManager;
    private ContextReducer contextReducer;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        sanitizer = new ObservationSanitizer(objectMapper);
        budgetManager = new ContextBudgetManager();
        contextReducer = new ContextReducer();
    }

    @Test
    @DisplayName("测试 ObservationSanitizer 将基金量化 JSON 净化为紧凑事实")
    void testSanitizeFundMetrics() {
        String rawJson = """
            {
              "fundCode": "005827",
              "fundName": "易方达蓝筹精选混合",
              "fundType": "偏股混合型",
              "annualizedReturn": 15.5,
              "maxDrawdown": 18.2,
              "sharpeRatio": 1.35,
              "primarySector": "食品饮料",
              "primarySectorRatio": 38.5,
              "cumulativeReturn": null,
              "calmarRatio": null
            }
            """;

        String sanitized = sanitizer.sanitizeFundMetrics(rawJson);
        assertTrue(sanitized.contains("易方达蓝筹精选混合 (代码: 005827, 类型: 偏股混合型)"));
        assertTrue(sanitized.contains("年化收益率 15.5%"));
        assertTrue(sanitized.contains("区间最大回撤 18.2%"));
        assertTrue(sanitized.contains("夏普比率(超额回报/总风险) 1.35"));
        assertTrue(sanitized.contains("第一重仓行业: 食品饮料 (38.5%)"));
        assertFalse(sanitized.contains("null")); // 彻底消除 null
        assertFalse(sanitized.contains("\"fundCode\"")); // 消除 JSON 括号噪声
    }

    @Test
    @DisplayName("测试 ObservationSanitizer 净化重仓持股 JSON")
    void testSanitizeHoldings() {
        String rawJson = """
            [
              {"stockCode": "600519", "stockName": "贵州茅台", "holdingRatio": 9.85, "holdingSector": "食品饮料"},
              {"stockCode": "00700", "stockName": "腾讯控股", "holdingRatio": 8.50, "holdingSector": "传媒通信"}
            ]
            """;

        String sanitized = sanitizer.sanitizeHoldings(rawJson);
        assertTrue(sanitized.contains("1. 贵州茅台 (600519): 占比 9.85%, 所属板块: 食品饮料"));
        assertTrue(sanitized.contains("2. 腾讯控股 (00700): 占比 8.5"));
        assertTrue(sanitized.contains("传媒通信"));
        assertTrue(sanitized.contains("前十大持仓合计净值占比: 18.35%"));
    }

    @Test
    @DisplayName("测试 StructuredUserPromptBuilder 标准分槽构建")
    void testStructuredUserPromptBuilder() {
        UserInvestmentProfile profile = UserInvestmentProfile.builder()
                .riskToleranceLevel(RiskToleranceLevel.C4)
                .maxDrawdownTolerance(new BigDecimal("20.00"))
                .targetAnnualReturn(new BigDecimal("18.00"))
                .preferredSectors(List.of("医药", "半导体"))
                .build();

        String prompt = StructuredUserPromptBuilder.create()
                .userGoal("推荐今年性价比最高的医药成长基金")
                .userProfile(profile)
                .addObservation("FUND_METRICS", "年化收益率: 22.5%, 最大回撤: 14.1%")
                .addHistoricalMemory("用户曾关注过中欧医疗健康混合")
                .addConstraint("严禁推荐成立不满3年的次新基金")
                .build();

        assertTrue(prompt.contains("【用户核心投研诉求】:"));
        assertTrue(prompt.contains("推荐今年性价比最高的医药成长基金"));
        assertTrue(prompt.contains("【适格投资者画像与风控约束】:"));
        assertTrue(prompt.contains("C4"));
        assertTrue(prompt.contains("【客观金融数据观察事实 (Tool Observations)】:"));
        assertTrue(prompt.contains("### [FUND_METRICS]"));
        assertTrue(prompt.contains("【前序关键记忆与决策共识】:"));
        assertTrue(prompt.contains("中欧医疗健康混合"));
        assertTrue(prompt.contains("【附加纪律与约束】:"));
        assertTrue(prompt.contains("严禁推荐成立不满3年的次新基金"));
    }

    @Test
    @DisplayName("测试 ContextReducer 滚动摘要压缩机制")
    void testContextReducerRollingSummary() {
        List<String> messages = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            messages.add("Executed step " + i + ": 完成模块 " + i + " 分析，输出结论事实详细说明内容...");
        }

        // 设置较小阈值迫使前 5 条被压缩 (总共约 87 tokens, 阈值 50 会触发压缩)
        List<String> reduced = contextReducer.compressAndReduce(messages, 50);

        assertTrue(reduced.size() < messages.size());
        assertTrue(reduced.get(0).contains("【前期历史交互摘要】"));
        assertTrue(reduced.get(0).contains("步骤/轮次1"));
    }

    @Test
    @DisplayName("测试 ContextBudgetManager Token 预算估算与截断")
    void testContextBudgetManager() {
        String shortText = "这是一段用于测试预算管理的金融投研分析文本。";
        int tokens = budgetManager.estimateTokens(shortText);
        assertTrue(tokens > 0);
        assertTrue(budgetManager.isWithinBudget(shortText, 100));

        String longText = "大模型输入事实 ".repeat(500);
        assertFalse(budgetManager.isWithinBudget(longText, 200));

        String truncated = budgetManager.truncateToBudget(longText, 200);
        assertTrue(budgetManager.estimateTokens(truncated) <= 250);
        assertTrue(truncated.contains("截断"));
    }
}
