package com.financial.copilot.agent.core.skill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <h1>Agent 技能意图按需动态匹配器 (Agent Skill Matcher)</h1>
 * <p>
 * 核心设计原则：按需动态挂载与零 Token 占用。
 * 在特定子任务执行时，结合子任务类型与用户 Query 意图动态匹配最适用的 Skill 规则；
 * 命中时提取规范正文并挂载至 Prompt Context，未命中时严格返回空字符串，绝不让无关技能侵占昂贵的模型上下文。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillMatcher {

    private final SkillRegistry skillRegistry;

    /**
     * 针对特定任务类型和用户指令，匹配适用的技能规则正文
     *
     * @param taskType  当前子任务类型 (如 SCREENING, BATCH_ANALYSIS, COMPARISON, SYNTHESIS)
     * @param userQuery 用户指令或上下文
     * @return 规整后的技能指导规则（若未命中任何技能则返回空字符串，0 Token 占用）
     */
    public String matchSkillInstructions(String taskType, String userQuery) {
        List<SkillDefinition> matched = matchSkills(taskType, userQuery);
        if (matched.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (SkillDefinition skill : matched) {
            log.info("[SKILL-MATCHER] 成功按需命中技能规范: name={}, taskType={}", skill.getName(), taskType);
            sb.append("#### 【行业专项规范: ").append(skill.getName()).append(" - ").append(skill.getDescription()).append("】\n")
              .append(skill.getRulesContent()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 检索匹配的技能定义列表
     *
     * @param taskType  当前子任务类型
     * @param userQuery 用户指令或意图
     * @return 命中的技能定义不可变列表
     */
    public List<SkillDefinition> matchSkills(String taskType, String userQuery) {
        String lowerQuery = (userQuery != null) ? userQuery.toLowerCase().trim() : "";
        List<SkillDefinition> matched = new ArrayList<>();

        // 1. 优先按子任务类型筛选候选技能集
        List<SkillDefinition> typeCandidates = (taskType != null && !taskType.isBlank())
                ? skillRegistry.findSkillsByTaskType(taskType)
                : List.of();

        for (SkillDefinition skill : typeCandidates) {
            List<String> triggers = skill.getTriggerKeywords();
            // 若技能未配置触发词，视为该任务类型的通用强制规范
            if (triggers == null || triggers.isEmpty()) {
                matched.add(skill);
            } else if (!lowerQuery.isEmpty()) {
                // 仅当用户意图匹配触发关键词时才动态装载
                boolean keywordMatched = triggers.stream()
                        .anyMatch(kw -> kw != null && !kw.isBlank() && lowerQuery.contains(kw.toLowerCase().trim()));
                if (keywordMatched) {
                    matched.add(skill);
                }
            }
        }

        // 2. 补充检索：若基于子任务未命中，或跨任务通用意图检索
        if (matched.isEmpty() && !lowerQuery.isEmpty()) {
            for (SkillDefinition skill : skillRegistry.getAllSkills()) {
                if (matched.contains(skill)) continue;
                List<String> triggers = skill.getTriggerKeywords();
                if (triggers != null && !triggers.isEmpty()) {
                    boolean keywordMatched = triggers.stream()
                            .anyMatch(kw -> kw != null && !kw.isBlank() && lowerQuery.contains(kw.toLowerCase().trim()));
                    if (keywordMatched) {
                        matched.add(skill);
                    }
                }
            }
        }

        return Collections.unmodifiableList(matched);
    }
}
