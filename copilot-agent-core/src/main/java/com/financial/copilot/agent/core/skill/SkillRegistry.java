package com.financial.copilot.agent.core.skill;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>Agent 技能库发现与多维索引注册中心 (Agent Skill Registry)</h1>
 * <p>
 * 启动时自动扫描 {@code classpath*:skills/**\/SKILL.md} 文件，严谨解析 YAML 头部元数据与 Markdown 规则正文，
 * 建立内存四大核心多维索引（技能名、子任务类型、触发词、资产类别），支持毫秒级按需检索。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class SkillRegistry {

    /**
     * 按技能名索引 (name.toLowerCase() -> SkillDefinition)
     */
    private final Map<String, SkillDefinition> skillNameIndex = new ConcurrentHashMap<>();

    /**
     * 按子任务类型索引 (taskType.toUpperCase() -> Set<SkillDefinition>)
     */
    private final Map<String, Set<SkillDefinition>> taskTypeIndex = new ConcurrentHashMap<>();

    /**
     * 按触发词多维倒排索引 (keyword.toLowerCase() -> Set<SkillDefinition>)
     */
    private final Map<String, Set<SkillDefinition>> keywordIndex = new ConcurrentHashMap<>();

    /**
     * 按资产大类索引 (assetCategory.toUpperCase() -> Set<SkillDefinition>)
     */
    private final Map<String, Set<SkillDefinition>> assetCategoryIndex = new ConcurrentHashMap<>();

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
            log.info("[SKILL-REGISTRY] 扫描到 {} 个技能定义规范文件", resources.length);

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
     * 注册单个技能定义并原子构建多维索引
     *
     * @param skill 技能实体
     */
    public synchronized void registerSkill(SkillDefinition skill) {
        if (skill == null || skill.getName() == null || skill.getName().isBlank()) {
            throw new IllegalArgumentException("Skill name must not be blank");
        }
        String nameKey = skill.getName().trim().toLowerCase();
        skillNameIndex.put(nameKey, skill);

        // 1. 任务类型多维索引构建
        if (skill.getTaskTypes() != null) {
            for (String type : skill.getTaskTypes()) {
                if (type != null && !type.isBlank()) {
                    taskTypeIndex.computeIfAbsent(type.trim().toUpperCase(), k -> ConcurrentHashMap.newKeySet()).add(skill);
                }
            }
        }

        // 2. 触发关键词多维倒排索引构建
        if (skill.getTriggerKeywords() != null) {
            for (String kw : skill.getTriggerKeywords()) {
                if (kw != null && !kw.isBlank()) {
                    keywordIndex.computeIfAbsent(kw.trim().toLowerCase(), k -> ConcurrentHashMap.newKeySet()).add(skill);
                }
            }
        }

        // 3. 资产大类多维索引构建
        String category = (skill.getAssetCategory() != null && !skill.getAssetCategory().isBlank())
                ? skill.getAssetCategory().trim().toUpperCase() : "ALL";
        assetCategoryIndex.computeIfAbsent(category, k -> ConcurrentHashMap.newKeySet()).add(skill);

        log.info("[SKILL-REGISTRY] 成功注册技能并建立多维索引: name={}, taskTypes={}, keywords={}, category={}",
                skill.getName(), skill.getTaskTypes(), skill.getTriggerKeywords(), category);
    }

    /**
     * 依据技能唯一名称精准检索
     *
     * @param name 技能名称
     * @return 技能实体 Optional
     */
    public Optional<SkillDefinition> getSkill(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(skillNameIndex.get(name.trim().toLowerCase()));
    }

    /**
     * 获取全量已注册技能清单
     *
     * @return 技能只读集合
     */
    public Collection<SkillDefinition> getAllSkills() {
        return Collections.unmodifiableCollection(skillNameIndex.values());
    }

    /**
     * 依据子任务类型检索关联技能集
     *
     * @param taskType 子任务类型 (如 SCREENING, COMPARISON, SYNTHESIS)
     * @return 技能列表
     */
    public List<SkillDefinition> findSkillsByTaskType(String taskType) {
        if (taskType == null || taskType.isBlank()) return Collections.emptyList();
        Set<SkillDefinition> skills = taskTypeIndex.get(taskType.trim().toUpperCase());
        return skills != null ? List.copyOf(skills) : Collections.emptyList();
    }

    /**
     * 依据意图关键词通过倒排索引检索候选技能集
     *
     * @param keyword 触发词
     * @return 命中技能列表
     */
    public List<SkillDefinition> findSkillsByKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return Collections.emptyList();
        Set<SkillDefinition> skills = keywordIndex.get(keyword.trim().toLowerCase());
        return skills != null ? List.copyOf(skills) : Collections.emptyList();
    }

    /**
     * 依据资产大类检索可用技能集
     *
     * @param assetCategory 资产大类 (如 FUND, STOCK, ALL)
     * @return 命中技能列表
     */
    public List<SkillDefinition> findSkillsByAssetCategory(String assetCategory) {
        if (assetCategory == null || assetCategory.isBlank()) return Collections.emptyList();
        Set<SkillDefinition> skills = assetCategoryIndex.get(assetCategory.trim().toUpperCase());
        return skills != null ? List.copyOf(skills) : Collections.emptyList();
    }

    /**
     * 解析单个 SKILL.md 资源文件（支持 YAML Frontmatter 与 Markdown 正文）
     *
     * @param resource 资源文件
     * @return 技能实体
     * @throws Exception 解析异常
     */
    public SkillDefinition parseSkillResource(Resource resource) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            List<String> lines = reader.lines().toList();
            if (lines.isEmpty()) return null;

            // 检查是否有 --- 前置 YAML Frontmatter
            if (!lines.get(0).trim().equals("---")) {
                String filename = resource.getFilename();
                return SkillDefinition.builder()
                        .name(filename != null ? filename.replace(".md", "") : "unnamed-skill")
                        .rulesContent(String.join("\n", lines))
                        .build();
            }

            // 定位第二个 --- 分隔符
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

            List<String> currentList = null;

            for (String line : yamlLines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                // 列表条目解析 (- item)
                if (trimmed.startsWith("- ") && currentList != null) {
                    currentList.add(cleanYamlValue(trimmed.substring(2)));
                    continue;
                }

                int colonIdx = trimmed.indexOf(':');
                if (colonIdx > 0) {
                    String key = trimmed.substring(0, colonIdx).trim();
                    String rawVal = trimmed.substring(colonIdx + 1).trim();
                    String val = cleanYamlValue(rawVal);

                    if ("name".equalsIgnoreCase(key)) {
                        builder.name(val);
                        currentList = null;
                    } else if ("description".equalsIgnoreCase(key)) {
                        builder.description(val);
                        currentList = null;
                    } else if ("assetCategory".equalsIgnoreCase(key)) {
                        builder.assetCategory(val);
                        currentList = null;
                    } else if ("taskTypes".equalsIgnoreCase(key)) {
                        currentList = new ArrayList<>();
                        builder.taskTypes(currentList);
                        parseInlineListIfPresent(rawVal, currentList);
                    } else if ("triggerKeywords".equalsIgnoreCase(key)) {
                        currentList = new ArrayList<>();
                        builder.triggerKeywords(currentList);
                        parseInlineListIfPresent(rawVal, currentList);
                    } else {
                        currentList = null;
                    }
                }
            }

            return builder.build();
        }
    }

    private String cleanYamlValue(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // 去除尾部注释
        int hashIdx = s.indexOf('#');
        if (hashIdx >= 0) {
            s = s.substring(0, hashIdx).trim();
        }
        // 去除外层引号
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            if (s.length() >= 2) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    private void parseInlineListIfPresent(String rawVal, List<String> targetList) {
        if (rawVal == null || rawVal.isBlank()) return;
        String s = rawVal.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            String content = s.substring(1, s.length() - 1);
            Arrays.stream(content.split(","))
                    .map(this::cleanYamlValue)
                    .filter(item -> !item.isBlank())
                    .forEach(targetList::add);
        } else if (!s.startsWith("-")) {
            String cleaned = cleanYamlValue(s);
            if (!cleaned.isBlank()) {
                targetList.add(cleaned);
            }
        }
    }
}
