package com.financial.copilot.agent.core.context;

import com.financial.copilot.domain.user.entity.UserInvestmentProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * <h1>结构化用户上下文构建器 (Structured User Prompt Builder)</h1>
 * <p>
 * 遵循 Context Engineering 原则，防止把自然语言提问、业务状态、画像、记忆与工具 Observation 随意揉杂。
 * 提供标准分槽隔离，确保进入 LLM 的上下文层级清晰、信息边界明确。
 * </p>
 *
 * @author FinancialCopilot
 */
public class StructuredUserPromptBuilder {

    private String userGoal;
    private UserInvestmentProfile userProfile;
    private final List<ObservationEntry> observations = new ArrayList<>();
    private final List<String> historicalMemories = new ArrayList<>();
    private final List<String> skillRules = new ArrayList<>();
    private final List<String> additionalConstraints = new ArrayList<>();

    private record ObservationEntry(String source, String content) {}

    public static StructuredUserPromptBuilder create() {
        return new StructuredUserPromptBuilder();
    }

    public StructuredUserPromptBuilder userGoal(String userGoal) {
        this.userGoal = userGoal;
        return this;
    }

    public StructuredUserPromptBuilder userProfile(UserInvestmentProfile userProfile) {
        this.userProfile = userProfile;
        return this;
    }

    public StructuredUserPromptBuilder addObservation(String source, String content) {
        if (content != null && !content.isBlank()) {
            this.observations.add(new ObservationEntry(source, content));
        }
        return this;
    }

    public StructuredUserPromptBuilder addHistoricalMemory(List<String> memories) {
        if (memories != null) {
            memories.stream().filter(m -> m != null && !m.isBlank()).forEach(this.historicalMemories::add);
        }
        return this;
    }

    public StructuredUserPromptBuilder addHistoricalMemory(String memory) {
        if (memory != null && !memory.isBlank()) {
            this.historicalMemories.add(memory);
        }
        return this;
    }

    public StructuredUserPromptBuilder addSkillRule(String skillRule) {
        if (skillRule != null && !skillRule.isBlank()) {
            this.skillRules.add(skillRule);
        }
        return this;
    }

    public StructuredUserPromptBuilder addConstraint(String constraint) {
        if (constraint != null && !constraint.isBlank()) {
            this.additionalConstraints.add(constraint);
        }
        return this;
    }

    public String build() {
        StringBuilder sb = new StringBuilder();

        // 1. 用户核心诉求
        if (userGoal != null && !userGoal.isBlank()) {
            sb.append("【用户核心投研诉求】:\n").append(userGoal.trim()).append("\n\n");
        }

        // 2. 适格投资者画像约束
        if (userProfile != null) {
            sb.append("【适格投资者画像与风控约束】:\n")
              .append(userProfile.toAgentPromptSummary().trim()).append("\n\n");
        }

        // 3. 命中业务规范与技能指导
        if (!skillRules.isEmpty()) {
            sb.append("【业务执行规范 (Skill Rules)】:\n");
            for (String rule : skillRules) {
                sb.append(rule.trim()).append("\n\n");
            }
        }

        // 4. 历史上下文与提纯记忆
        if (!historicalMemories.isEmpty()) {
            sb.append("【前序关键记忆与决策共识】:\n");
            for (String mem : historicalMemories) {
                sb.append("- ").append(mem.trim()).append("\n");
            }
            sb.append("\n");
        }

        // 5. 工具事实观察值
        if (!observations.isEmpty()) {
            sb.append("【客观金融数据观察事实 (Tool Observations)】:\n");
            for (ObservationEntry obs : observations) {
                sb.append("### [").append(obs.source()).append("]\n")
                  .append(obs.content().trim()).append("\n\n");
            }
        }

        // 6. 附加纪律与约束
        if (!additionalConstraints.isEmpty()) {
            sb.append("【附加纪律与约束】:\n");
            for (int i = 0; i < additionalConstraints.size(); i++) {
                sb.append(i + 1).append(". ").append(additionalConstraints.get(i).trim()).append("\n");
            }
        }

        return sb.toString().trim();
    }
}
