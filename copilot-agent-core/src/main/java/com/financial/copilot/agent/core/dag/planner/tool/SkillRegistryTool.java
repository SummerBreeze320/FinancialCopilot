package com.financial.copilot.agent.core.dag.planner.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * <h1>技能注册中心探测工具 (SkillRegistryTool)</h1>
 * <p>
 * 查询并列举当前投研系统已装配的各垂直 Agent 专有能力契约与执行规范。
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

    private static final List<SkillDescriptor> REGISTERED_SKILLS = List.of(
            new SkillDescriptor("fund-screener", "SCREENING", "公募基金多维量化筛选能力", List.of("screening_criteria"), "FUND_POOL"),
            new SkillDescriptor("fund-analyzer", "BATCH_ANALYSIS", "基金及基金经理能力圈体检打分能力", List.of("fund_codes"), "FUND_RESEARCH"),
            new SkillDescriptor("fund-comparator", "COMPARISON", "决赛圈标的深度定量与定性季报对标能力", List.of("top_candidates"), "COMPARISON_REPORT"),
            new SkillDescriptor("report-synthesizer", "SYNTHESIS", "专业投研研报长文本终审合成能力", List.of("facts"), "FINAL_REPORT"),
            new SkillDescriptor("macro-analyzer", "MACRO", "宏观流动性与大类资产轮动定调能力", List.of("macro_indicators"), "MACRO_FACTS"),
            new SkillDescriptor("asset-allocation", "ALLOCATION", "大类资产配置比例与股债平衡测算能力", List.of("user_profile"), "ALLOCATION_PLAN")
    );

    public List<SkillDescriptor> listSkills() {
        return REGISTERED_SKILLS;
    }

    public Optional<SkillDescriptor> findSkill(String skillName) {
        if (skillName == null) return Optional.empty();
        return REGISTERED_SKILLS.stream()
                .filter(s -> s.skillName().equalsIgnoreCase(skillName))
                .findFirst();
    }

    public Optional<SkillDescriptor> findByTaskType(String taskType) {
        if (taskType == null) return Optional.empty();
        return REGISTERED_SKILLS.stream()
                .filter(s -> s.taskType().equalsIgnoreCase(taskType))
                .findFirst();
    }
}
