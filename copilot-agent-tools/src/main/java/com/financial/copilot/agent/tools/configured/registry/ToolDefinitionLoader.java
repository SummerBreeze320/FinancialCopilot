package com.financial.copilot.agent.tools.configured.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.tools.configured.model.ToolDefinition;
import com.financial.copilot.agent.tools.configured.model.ToolParameterDefinition;
import com.financial.copilot.agent.tools.configured.model.UITreeComponent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

/**
 * 模块化 Tool 配置加载器。
 * 遵循“第一层区分深度分析(ANALYSIS) vs 横向对比(COMPARISON)，
 * 自动扫描 classpath*:config/tools/** 下的所有工具配置文件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolDefinitionLoader {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final ToolProperties properties;

    /**
     * 扫描并加载所有模块化 ToolDefinition。
     *
     * @return 解析完成的 Tool 列表（包含 Scope、Tool 及其挂载的一组组件）
     */
    public List<ToolDefinition> loadDefinitions() {
        String pattern = properties.getConfigPattern();
        log.info("Scanning and loading modular tool definitions from: {}", pattern);
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(resourceLoader);
            Resource[] resources = resolver.getResources(pattern);

            if (resources == null || resources.length == 0) {
                log.warn("No modular tool definition files found matching: {}", pattern);
                return Collections.emptyList();
            }

            List<ToolDefinition> result = new ArrayList<>();
            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    JsonNode root = objectMapper.readTree(is);
                    ToolDefinition def = parseToolFile(root, resource.getFilename());
                    if (def != null) {
                        result.add(def);
                    }
                } catch (Exception e) {
                    log.error("Failed to parse tool definition file: {}", resource.getFilename(), e);
                }
            }

            result.sort(Comparator.comparing(t -> t.getSort() == null ? 99 : t.getSort()));
            log.info("Successfully loaded {} modular tools (totaling {} components)",
                    result.size(), result.stream().mapToInt(t -> t.getComponents() == null ? 0 : t.getComponents().size()).sum());
            return result;
        } catch (Exception e) {
            log.error("Failed to scan tool definitions pattern: {}", pattern, e);
            throw new IllegalStateException("Tool configuration loading failed: " + pattern, e);
        }
    }

    private ToolDefinition parseToolFile(JsonNode node, String filename) {
        String toolId = node.path("toolId").asText(node.path("id").asText(filename.replace(".json", "")));
        String name = node.path("name").asText(toolId);
        String scope = node.path("scope").asText("SINGLE_FUND_ANALYSIS");
        String category = node.path("category").asText("");
        String toolType = node.path("toolType").asText("SINGLE_FUND_TOOL");
        int sort = node.path("sort").asInt(99);
        String desc = node.path("description").asText("");
        String provider = node.path("provider").asText("mcp");
        String server = node.path("server").isNull() ? null : node.path("server").asText(null);
        String targetTool = node.path("targetTool").isNull() ? null : node.path("targetTool").asText(null);
        String templateId = node.path("templateId").isNull() ? null : node.path("templateId").asText(null);
        String dateMode = node.path("dateMode").isNull() ? null : node.path("dateMode").asText(null);

        // 解析参数列表
        List<ToolParameterDefinition> params = new ArrayList<>();
        JsonNode paramsNode = node.path("parameters");
        if (paramsNode.isArray()) {
            for (JsonNode p : paramsNode) {
                params.add(ToolParameterDefinition.builder()
                        .name(p.path("name").asText())
                        .type(p.path("type").asText("string"))
                        .required(p.path("required").asBoolean(false))
                        .defaultValue(p.has("defaultValue") ? p.get("defaultValue").asText(null) : null)
                        .description(p.path("description").asText(""))
                        .build());
            }
        }

        // 解析绑定的一组组件 (Components)
        List<UITreeComponent> components = new ArrayList<>();
        JsonNode compsNode = node.path("components");
        if (compsNode.isArray()) {
            for (JsonNode c : compsNode) {
                components.add(parseComponent(c, category));
            }
        }

        Map<String, Object> rawMeta = objectMapper.convertValue(node, Map.class);

        return ToolDefinition.builder()
                .id(toolId)
                .name(name)
                .scope(scope)
                .category(category)
                .toolType(toolType)
                .sort(sort)
                .description(desc)
                .provider(provider)
                .server(server)
                .targetTool(targetTool)
                .templateId(templateId)
                .dateMode(dateMode)
                .parameters(params)
                .componentCount(components.size())
                .components(components)
                .rawMetadata(rawMeta)
                .build();
    }

    private UITreeComponent parseComponent(JsonNode node, String category) {
        String compId = node.path("componentId").asText(node.path("id").asText(""));
        String name = node.path("name").asText(compId);
        String cardTitle = node.path("cardTitle").asText(name);
        String displayType = node.path("displayType").asText("table");
        String desc = node.path("description").asText("");
        String url = node.path("url").isNull() ? null : node.path("url").asText(null);

        List<String> linkId = new ArrayList<>();
        if (node.has("linkId") && node.get("linkId").isArray()) {
            node.get("linkId").forEach(item -> linkId.add(item.asText()));
        }

        Map<String, Object> meta = new HashMap<>();
        if (node.has("metadata") && node.get("metadata").isObject()) {
            meta = objectMapper.convertValue(node.get("metadata"), Map.class);
        }

        return UITreeComponent.builder()
                .id(compId)
                .name(name)
                .cardTitle(cardTitle)
                .displayType(displayType)
                .category(category)
                .metadata(meta)
                .linkId(linkId)
                .url(url)
                .build();
    }
}
