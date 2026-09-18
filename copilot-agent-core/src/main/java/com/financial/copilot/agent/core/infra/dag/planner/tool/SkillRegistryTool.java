package com.financial.copilot.agent.core.infra.dag.planner.tool;

import com.financial.copilot.agent.core.infra.skill.SkillDefinition;
import com.financial.copilot.agent.core.infra.skill.SkillRegistry;
import com.financial.copilot.agent.core.business.agents.AgentRoleCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * <h1>技能注册中心探测工具 (SkillRegistryTool)</h1>
 * <p>
 * 查询并列举当前投研系统已装配的各垂直 Agent 专有能力契约与执行规范。
 * 支持从类路径 {@link SkillRegistry} 动态发现与内置描述符双轨并存。
 * </p>
 */
@Component
public class SkillRegistryTool {

    public record SkillDescriptor(
            String skillName,
            String taskType,
            String description,
            List<String> requiredInputs,
            String outputType
    ) {}

    private final SkillRegistry skillRegistry;

    private static final List<SkillDescriptor> BUILTIN_SKILLS = List.of(
            new SkillDescriptor("fund-screener", "SCREENING", "公募基金多维量化筛选能力", List.of("screening_criteria"), "FUND_POOL"),
            new SkillDescriptor("fund-analyzer", "BATCH_ANALYSIS", "基金及基金经理能力圈体检打分能力", List.of("fund_codes"), "FUND_RESEARCH"),
            new SkillDescriptor("fund-comparator", "COMPARISON", "决赛圈标的深度定量与定性季报对标能力", List.of("top_candidates"), "COMPARISON_REPORT"),
            new SkillDescriptor("report-synthesizer", "SYNTHESIS", "专业投研研报长文本终审合成能力", List.of("facts"), "FINAL_REPORT")
    );

    public SkillRegistryTool() {
        this(null);
    }

    @Autowired
    public SkillRegistryTool(@Autowired(required = false) SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public List<SkillDescriptor> listSkills() {
        if (skillRegistry == null) {
            return BUILTIN_SKILLS;
        }
        List<SkillDescriptor> result = new ArrayList<>(BUILTIN_SKILLS);
        for (SkillDefinition sd : skillRegistry.getAllSkills()) {
            String taskType = (sd.getTaskTypes() != null && !sd.getTaskTypes().isEmpty()) ? sd.getTaskTypes().get(0) : "GENERAL";
            if (!AgentRoleCatalog.supports(taskType)) continue;
            boolean exists = result.stream().anyMatch(s -> s.skillName().equalsIgnoreCase(sd.getName()));
            if (!exists) {
                result.add(new SkillDescriptor(
                        sd.getName(),
                        taskType,
                        sd.getDescription(),
                        List.of(),
                        "GENERAL"
                ));
            }
        }
        return result;
    }

    public Optional<SkillDescriptor> findSkill(String skillName) {
        if (skillName == null) return Optional.empty();
        return listSkills().stream()
                .filter(s -> s.skillName().equalsIgnoreCase(skillName))
                .findFirst();
    }

    public Optional<SkillDescriptor> findByTaskType(String taskType) {
        if (taskType == null) return Optional.empty();
        return listSkills().stream()
                .filter(s -> s.taskType().equalsIgnoreCase(taskType))
                .findFirst();
    }
}
