package com.financial.copilot.agent.core.skill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <h1>Agent 技能意图匹配器 (Agent Skill Matcher)</h1>
 * <p>
 * 核心设计原则：按需加载。在特定任务执行或针对用户提问时，动态评估并匹配最适合的 Skill 规范；
 * 命中则提取规则正文并装配进 Prompt Context；未命中则返回空，绝不把全量规则塞进上下文。
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
     * @param taskType  当前子任务类型 (如 COMPARISON, SYNTHESIS, SCREENING)
     * @param userQuery 用户指令或上下文
     * @return 规整后的技能指导规则（若未命中任何技能则返回空字符串）
     */
    public String matchSkillInstructions(String taskType, String userQuery) {
        List<SkillDefinition> matched = matchSkills(taskType, userQuery);
        if (matched.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (SkillDefinition skill : matched) {
            log.info("[SKILL-MATCHER] 成功命中技能规范: name={}, taskType={}", skill.getName(), taskType);
            sb.append("#### 【行业专项规范: ").append(skill.getName()).append(" - ").append(skill.getDescription()).append("】\n")
              .append(skill.getRulesContent()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 检索匹配的技能定义列表
     */
    public List<SkillDefinition> matchSkills(String taskType, String userQuery) {
        List<SkillDefinition> candidates = skillRegistry.findSkillsByTaskType(taskType);
        if (candidates.isEmpty()) {
            // 如果没有按任务类型匹配的，尝试从所有技能中按关键词查找
            candidates = new ArrayList<>(skillRegistry.getAllSkills());
        }

        String lowerQuery = (userQuery != null) ? userQuery.toLowerCase() : "";

        return candidates.stream().filter(skill -> {
            // 如果技能指定了适用的 taskType，必须优先匹配
            if (taskType != null && !taskType.isBlank() && skill.getTaskTypes() != null && !skill.getTaskTypes().isEmpty()) {
                boolean typeMatched = skill.getTaskTypes().stream().anyMatch(t -> t.equalsIgnoreCase(taskType));
                if (typeMatched) {
                    return true;
                }
            }

            // 检查关键词触发
            if (!lowerQuery.isEmpty() && skill.getTriggerKeywords() != null && !skill.getTriggerKeywords().isEmpty()) {
                return skill.getTriggerKeywords().stream().anyMatch(kw -> lowerQuery.contains(kw.toLowerCase()));
            }

            return false;
        }).collect(Collectors.toList());
    }
}
