package com.financial.copilot.agent.core.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <h1>Agent 技能体系端到端自动化单元测试 (Skill System Test)</h1>
 * <p>
 * 验证核心能力：
 * 1. 类路径 SKILL.md 自动扫描与 YAML Frontmatter 规范解析；
 * 2. 四维高并发索引（技能名、任务类型、触发词、资产大类）；
 * 3. 基于子任务类型与用户 Query 意图的动态挂载；
 * 4. 严格未命中时 0 长度空串输出（零 Token 占用契约）；
 * 5. 动态技能注册与实时索引扩展能力。
 * </p>
 *
 * @author FinancialCopilot
 */
class SkillSystemTest {

    private SkillRegistry registry;
    private SkillMatcher matcher;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
        registry.init();
        matcher = new SkillMatcher(registry);
    }

    @Test
    @DisplayName("测试类路径自动扫描加载 6 个行业内建 SKILL.md 规范")
    void testClasspathScanningAllSixSkills() {
        var allSkills = registry.getAllSkills();
        assertEquals(6, allSkills.size(), "应成功扫描并注册全部 6 个行业内建技能文件");

        // 1. quant-screening
        Optional<SkillDefinition> quantScreening = registry.getSkill("quant-screening");
        assertTrue(quantScreening.isPresent());
        assertEquals("FUND", quantScreening.get().getAssetCategory());
        assertTrue(quantScreening.get().getTaskTypes().contains("SCREENING"));
        assertTrue(quantScreening.get().getRulesContent().contains("基金规模边界与流动性考量"));

        // 2. fund-analysis
        Optional<SkillDefinition> fundAnalysis = registry.getSkill("fund-analysis");
        assertTrue(fundAnalysis.isPresent());
        assertEquals("FUND", fundAnalysis.get().getAssetCategory());
        assertTrue(fundAnalysis.get().getTaskTypes().contains("BATCH_ANALYSIS"));
        assertTrue(fundAnalysis.get().getTaskTypes().contains("QUANT"));
        assertTrue(fundAnalysis.get().getRulesContent().contains("风险调整后收益三维评估"));

        // 3. fund-comparison
        Optional<SkillDefinition> fundComp = registry.getSkill("fund-comparison");
        assertTrue(fundComp.isPresent());
        assertEquals("FUND", fundComp.get().getAssetCategory());
        assertTrue(fundComp.get().getTaskTypes().contains("COMPARISON"));
        assertTrue(fundComp.get().getRulesContent().contains("风险-收益特征矩阵对称性要求"));

        // 4. asset-allocation
        Optional<SkillDefinition> allocation = registry.getSkill("asset-allocation");
        assertTrue(allocation.isPresent());
        assertEquals("FUND", allocation.get().getAssetCategory());
        assertTrue(allocation.get().getTaskTypes().contains("SYNTHESIS"));
        assertTrue(allocation.get().getRulesContent().contains("核心-卫星组合架构"));

        // 5. report-synthesis
        Optional<SkillDefinition> reportSynthesis = registry.getSkill("report-synthesis");
        assertTrue(reportSynthesis.isPresent());
        assertEquals("FUND", reportSynthesis.get().getAssetCategory());
        assertTrue(reportSynthesis.get().getTaskTypes().contains("SYNTHESIS"));
        assertTrue(reportSynthesis.get().getRulesContent().contains("结构化四大核心板块"));

        // 6. macro-timing
        Optional<SkillDefinition> macroTiming = registry.getSkill("macro-timing");
        assertTrue(macroTiming.isPresent());
        assertEquals("FUND", macroTiming.get().getAssetCategory());
        assertTrue(macroTiming.get().getTaskTypes().contains("MACRO"));
        assertTrue(macroTiming.get().getRulesContent().contains("经典美林投资时钟与周期定位"));
    }

    @Test
    @DisplayName("测试四维并发索引检索能力 (任务类型、触发词、资产大类)")
    void testMultiDimensionalIndexing() {
        // 1. 按任务类型索引检索
        List<SkillDefinition> screeningSkills = registry.findSkillsByTaskType("SCREENING");
        assertTrue(screeningSkills.size() >= 2, "SCREENING 任务类型应至少关联 quant-screening 与 macro-timing");

        List<SkillDefinition> synthesisSkills = registry.findSkillsByTaskType("SYNTHESIS");
        assertTrue(synthesisSkills.size() >= 2, "SYNTHESIS 任务类型应至少关联 asset-allocation 与 report-synthesis");

        // 2. 按关键词倒排索引检索
        List<SkillDefinition> timingByKeyword = registry.findSkillsByKeyword("宏观");
        assertFalse(timingByKeyword.isEmpty());
        assertTrue(timingByKeyword.stream().anyMatch(s -> s.getName().equals("macro-timing")));

        List<SkillDefinition> checkupByKeyword = registry.findSkillsByKeyword("体检");
        assertFalse(checkupByKeyword.isEmpty());
        assertTrue(checkupByKeyword.stream().anyMatch(s -> s.getName().equals("fund-analysis")));

        // 3. 按资产大类索引检索
        List<SkillDefinition> fundSkills = registry.findSkillsByAssetCategory("FUND");
        assertEquals(6, fundSkills.size(), "当前基金大类下应包含 6 个专精技能");
    }

    @Test
    @DisplayName("测试用户意图精准匹配与动态技能挂载")
    void testIntentDrivenDynamicMatching() {
        // 1. 用户提问包含对比/PK 意图
        String compRules = matcher.matchSkillInstructions("COMPARISON", "请帮我横向对比 005827 和 161005 的回撤差异");
        assertFalse(compRules.isBlank());
        assertTrue(compRules.contains("fund-comparison"));
        assertTrue(compRules.contains("重仓持仓穿透与风格漂移检验"));

        // 2. 用户提问包含深度体检/打分意图
        String analysisRules = matcher.matchSkillInstructions("BATCH_ANALYSIS", "请对 005827 开展全景深度体检并打分");
        assertFalse(analysisRules.isBlank());
        assertTrue(analysisRules.contains("fund-analysis"));
        assertTrue(analysisRules.contains("风险调整后收益三维评估"));

        // 3. 用户提问包含研报合成意图
        String reportRules = matcher.matchSkillInstructions("SYNTHESIS", "请最终合成一份专业机构级的投研报告");
        assertFalse(reportRules.isBlank());
        assertTrue(reportRules.contains("report-synthesis"));
        assertTrue(reportRules.contains("结构化四大核心板块"));

        // 4. 用户提问包含宏观择时与流动性意图
        String macroRules = matcher.matchSkillInstructions("SCREENING", "结合当前的宏观流动性周期进行初筛");
        assertFalse(macroRules.isBlank());
        assertTrue(macroRules.contains("macro-timing"));
        assertTrue(macroRules.contains("经典美林投资时钟与周期定位"));
    }

    @Test
    @DisplayName("测试未命中意图时严格返回空字符串，确保零 Token 占用契约")
    void testZeroTokenWhenNotMatched() {
        // 1. 任务类型不匹配且无相关触发词
        String unrelated = matcher.matchSkillInstructions("UNKNOWN_TASK", "今天的天气真的很不错，给我讲个笑话吧");
        assertEquals("", unrelated, "无关任务与无命中意图必须返回空字符串，确保 0 Token 占用");

        // 2. 属于 SCREENING 任务类型，但用户提问完全不包含筛选触发词（如只是纯数字代号或寒暄）
        String noKeywordScreening = matcher.matchSkillInstructions("SCREENING", "123456");
        assertEquals("", noKeywordScreening, "未触发 triggerKeywords 时不注入技能，保证 0 Token 占用");

        // 3. 属于 COMPARISON 任务类型，但 query 无任何对比意图
        String noKeywordComparison = matcher.matchSkillInstructions("COMPARISON", "005827");
        assertEquals("", noKeywordComparison, "未触发对比关键词时不侵占上下文");

        // 4. null / 空白字符串保护
        assertEquals("", matcher.matchSkillInstructions(null, null));
        assertEquals("", matcher.matchSkillInstructions("", ""));
        assertEquals("", matcher.matchSkillInstructions("SYNTHESIS", "   "));
    }

    @Test
    @DisplayName("测试动态注册与四维索引实时扩展")
    void testDynamicSkillRegistrationAndIndexUpdate() {
        SkillDefinition custom = SkillDefinition.builder()
                .name("stock-valuation-audit")
                .description("个股估值与财务穿透风控规范")
                .assetCategory("STOCK")
                .taskTypes(List.of("STOCK_AUDIT", "VALUATION"))
                .triggerKeywords(List.of("估值", "DCF", "财务造假", "爆雷"))
                .rulesContent("排查商誉减值与大股东高比例质押风险")
                .build();

        registry.registerSkill(custom);

        // 1. 验证根据名称获取
        Optional<SkillDefinition> found = registry.getSkill("stock-valuation-audit");
        assertTrue(found.isPresent());
        assertEquals("STOCK", found.get().getAssetCategory());

        // 2. 验证任务类型索引自动更新
        List<SkillDefinition> valuationSkills = registry.findSkillsByTaskType("VALUATION");
        assertFalse(valuationSkills.isEmpty());
        assertEquals("stock-valuation-audit", valuationSkills.get(0).getName());

        // 3. 验证触发词倒排索引自动更新
        List<SkillDefinition> dcfSkills = registry.findSkillsByKeyword("DCF");
        assertFalse(dcfSkills.isEmpty());
        assertEquals("stock-valuation-audit", dcfSkills.get(0).getName());

        // 4. 验证资产大类索引自动更新
        List<SkillDefinition> stockSkills = registry.findSkillsByAssetCategory("STOCK");
        assertFalse(stockSkills.isEmpty());
        assertTrue(stockSkills.stream().anyMatch(s -> s.getName().equals("stock-valuation-audit")));

        // 5. 验证动态意图匹配
        String matched = matcher.matchSkillInstructions("VALUATION", "请帮我排查该公司是否存在财务造假或爆雷风险");
        assertTrue(matched.contains("stock-valuation-audit"));
        assertTrue(matched.contains("商誉减值"));
    }
}
