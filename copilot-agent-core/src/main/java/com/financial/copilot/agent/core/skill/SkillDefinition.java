package com.financial.copilot.agent.core.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * <h1>Agent 技能定义实体 (Skill Definition)</h1>
 * <p>
 * 遵循 Agent Skills 架构设计：Skill 本身不提供工具能力，它解决的是“这类任务该按什么规则做”。
 * 由宿主程序按需检索匹配，并只在命中时将对应规范注入给 Agent 上下文，不命中绝不占用 Token。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDefinition {

    /**
     * 技能唯一标识名称 (例如: fund-comparison)
     */
    private String name;

    /**
     * 技能简要描述与定位
     */
    private String description;

    /**
     * 适用的资产大类 (FUND, STOCK, ALL 等)
     */
    @Builder.Default
    private String assetCategory = "ALL";

    /**
     * 适用的子任务类型 (SCREENING, BATCH_ANALYSIS, COMPARISON, SYNTHESIS)
     */
    @Builder.Default
    private List<String> taskTypes = new ArrayList<>();

    /**
     * 触发关键词列表 (如: 对比, 对标, 资产配置, 核心卫星)
     */
    @Builder.Default
    private List<String> triggerKeywords = new ArrayList<>();

    /**
     * 技能详细规则正文 (SKILL.md 的 Markdown 主体内容)
     */
    private String rulesContent;
}
