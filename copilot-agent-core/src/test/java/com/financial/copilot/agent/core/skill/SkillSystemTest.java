package com.financial.copilot.agent.core.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

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
    @DisplayName("测试 SkillRegistry 自动扫描类路径加载内置 SKILL.md")
    void testClasspathScanning() {
        var allSkills = registry.getAllSkills();
        assertFalse(allSkills.isEmpty(), "应成功扫描到内置技能文件");

        Optional<SkillDefinition> fundComp = registry.getSkill("fund-comparison");
        assertTrue(fundComp.isPresent());
        assertEquals("fund-comparison", fundComp.get().getName());
        assertTrue(fundComp.get().getTaskTypes().contains("COMPARISON"));
        assertTrue(fundComp.get().getRulesContent().contains("风险-收益特征矩阵对称性要求"));

        Optional<SkillDefinition> allocation = registry.getSkill("asset-allocation");
        assertTrue(allocation.isPresent());
        assertTrue(allocation.get().getTaskTypes().contains("SYNTHESIS"));
        assertTrue(allocation.get().getRulesContent().contains("核心-卫星组合架构"));
    }

    @Test
    @DisplayName("测试 SkillMatcher 按需动态匹配技能规则")
    void testSkillMatchingOnDemand() {
        // 1. 匹配 COMPARISON 任务
        String instructions = matcher.matchSkillInstructions("COMPARISON", "帮我对比 005827 和 161005");
        assertFalse(instructions.isBlank());
        assertTrue(instructions.contains("fund-comparison"));
        assertTrue(instructions.contains("重仓持仓穿透与风格漂移检验"));

        // 2. 匹配 SYNTHESIS 任务
        String synthRules = matcher.matchSkillInstructions("SYNTHESIS", "请生成资产配置投资建议");
        assertFalse(synthRules.isBlank());
        assertTrue(synthRules.contains("asset-allocation"));
        assertTrue(synthRules.contains("核心-卫星组合架构"));

        // 3. 不匹配无关请求
        String noMatch = matcher.matchSkillInstructions("UNKNOWN_TASK", "随便聊聊天");
        assertTrue(noMatch.isBlank(), "未命中技能应返回空字符串，不占用模型 Token");
    }

    @Test
    @DisplayName("测试动态注册与覆盖扩展 Skill")
    void testDynamicSkillRegistration() {
        SkillDefinition custom = SkillDefinition.builder()
                .name("custom-risk-audit")
                .description("自定义高级风控排查规范")
                .taskTypes(List.of("RISK_AUDIT"))
                .triggerKeywords(List.of("风控", "合规"))
                .rulesContent("排查大额赎回及关联方交易风险")
                .build();

        registry.registerSkill(custom);
        Optional<SkillDefinition> found = registry.getSkill("custom-risk-audit");
        assertTrue(found.isPresent());

        String matched = matcher.matchSkillInstructions("RISK_AUDIT", "合规风险排查");
        assertTrue(matched.contains("custom-risk-audit"));
        assertTrue(matched.contains("大额赎回"));
    }
}
