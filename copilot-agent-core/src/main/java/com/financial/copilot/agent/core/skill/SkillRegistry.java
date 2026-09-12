package com.financial.copilot.agent.core.skill;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * <h1>Agent 技能库发现与注册中心 (Agent Skill Registry)</h1>
 * <p>
 * 启动时自动扫描 classpath:skills/**\/SKILL.md 文件，解析 YAML 头部元数据与 Markdown 规则正文，
 * 建立内存多维索引（技能名、任务类型、触发词），支持按需检索。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class SkillRegistry {

    private final Map<String, SkillDefinition> skillMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadSkillsFromClasspath();
    }

    /**
     * 从类路径扫描并加载所有技能定义规范
     */
    public synchronized void loadSkillsFromClasspath() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:skills/**/SKILL.md");
            log.info("[SKILL-REGISTRY] 扫描到 {} 个技能定义文件", resources.length);

            for (Resource res : resources) {
                try {
                    SkillDefinition skill = parseSkillResource(res);
                    if (skill != null && skill.getName() != null) {
                        registerSkill(skill);
                    }
                } catch (Exception e) {
                    log.error("[SKILL-REGISTRY] 解析技能文件失败: resource={}", res.getFilename(), e);
                }
            }
        } catch (Exception e) {
            log.warn("[SKILL-REGISTRY] 扫描技能定义文件异常: {}", e.getMessage());
        }
    }

    /**
     * 注册技能定义
     */
    public void registerSkill(SkillDefinition skill) {
        if (skill == null || skill.getName() == null || skill.getName().isBlank()) {
            throw new IllegalArgumentException("Skill name must not be blank");
        }
        skillMap.put(skill.getName().trim().toLowerCase(), skill);
        log.info("[SKILL-REGISTRY] 成功注册技能: name={}, taskTypes={}, triggers={}",
                skill.getName(), skill.getTaskTypes(), skill.getTriggerKeywords());
    }

    public Optional<SkillDefinition> getSkill(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(skillMap.get(name.trim().toLowerCase()));
    }

    public Collection<SkillDefinition> getAllSkills() {
        return Collections.unmodifiableCollection(skillMap.values());
    }

    /**
     * 按子任务类型过滤技能
     */
    public List<SkillDefinition> findSkillsByTaskType(String taskType) {
        if (taskType == null || taskType.isBlank()) return Collections.emptyList();
        String upperType = taskType.trim().toUpperCase();
        return skillMap.values().stream()
                .filter(s -> s.getTaskTypes() != null && s.getTaskTypes().stream().anyMatch(t -> t.equalsIgnoreCase(upperType)))
                .collect(Collectors.toList());
    }

    /**
     * 解析单个 SKILL.md 资源文件
     */
    public SkillDefinition parseSkillResource(Resource resource) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            List<String> lines = reader.lines().toList();
            if (lines.isEmpty()) return null;

            // 检查是否有 --- 前置 YAML Frontmatter
            if (!lines.get(0).trim().equals("---")) {
                // 无 Frontmatter，回退使用文件名作为技能名
                String filename = resource.getFilename();
                return SkillDefinition.builder()
                        .name(filename != null ? filename.replace(".md", "") : "unnamed-skill")
                        .rulesContent(String.join("\n", lines))
                        .build();
            }

            // 解析 Frontmatter
            int secondSeparatorIndex = -1;
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).trim().equals("---")) {
                    secondSeparatorIndex = i;
                    break;
                }
            }

            if (secondSeparatorIndex == -1) {
                return null;
            }

            List<String> yamlLines = lines.subList(1, secondSeparatorIndex);
            List<String> markdownLines = lines.subList(secondSeparatorIndex + 1, lines.size());

            SkillDefinition.SkillDefinitionBuilder builder = SkillDefinition.builder();
            builder.rulesContent(String.join("\n", markdownLines).trim());

            String currentKey = null;
            List<String> currentList = null;

            for (String line : yamlLines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                if (trimmed.startsWith("- ") && currentList != null) {
                    currentList.add(trimmed.substring(2).trim());
                    continue;
                }

                int colonIdx = trimmed.indexOf(':');
                if (colonIdx > 0) {
                    String key = trimmed.substring(0, colonIdx).trim();
                    String val = trimmed.substring(colonIdx + 1).trim();

                    if ("name".equalsIgnoreCase(key)) {
                        builder.name(val);
                    } else if ("description".equalsIgnoreCase(key)) {
                        builder.description(val);
                    } else if ("assetCategory".equalsIgnoreCase(key)) {
                        builder.assetCategory(val);
                    } else if ("taskTypes".equalsIgnoreCase(key)) {
                        currentKey = "taskTypes";
                        currentList = new ArrayList<>();
                        builder.taskTypes(currentList);
                        if (!val.isEmpty()) {
                            currentList.add(val);
                        }
                    } else if ("triggerKeywords".equalsIgnoreCase(key)) {
                        currentKey = "triggerKeywords";
                        currentList = new ArrayList<>();
                        builder.triggerKeywords(currentList);
                        if (!val.isEmpty()) {
                            currentList.add(val);
                        }
                    } else {
                        currentKey = null;
                        currentList = null;
                    }
                }
            }

            return builder.build();
        }
    }
}
