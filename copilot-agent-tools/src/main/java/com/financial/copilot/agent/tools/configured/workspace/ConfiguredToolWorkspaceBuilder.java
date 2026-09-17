package com.financial.copilot.agent.tools.configured.workspace;

import com.financial.copilot.agent.tools.configured.model.ToolDefinition;
import com.financial.copilot.agent.tools.configured.model.ToolExecuteRequest;
import com.financial.copilot.agent.tools.configured.model.UITreeComponent;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 负责将工具执行定义、请求参数和结果组件转换为标准化工作台载荷及轻量引用。
 */
@Component
public class ConfiguredToolWorkspaceBuilder {

    private final ComponentInstanceIdFactory idFactory;
    private final AtomicInteger referenceSequence = new AtomicInteger(1);

    public ConfiguredToolWorkspaceBuilder(ComponentInstanceIdFactory idFactory) {
        this.idFactory = idFactory;
    }

    public ToolWorkspacePayload build(ToolDefinition definition,
                                      ToolExecuteRequest request,
                                      List<UITreeComponent> components) {
        int referenceId = referenceSequence.getAndIncrement();
        List<UITreeComponent> compList = components != null ? components : List.of();
        List<ToolWorkspaceComponent> workspaceComponents = compList.stream()
                .map(component -> toWorkspaceComponent(definition, request, component))
                .toList();

        String scope = definition != null && definition.getScope() != null ? definition.getScope() : "";
        String workspaceType = "COMPARISON".equalsIgnoreCase(scope)
                ? "FUND_COMPARISON"
                : "FUND_ANALYSIS";
        String defaultName = "FUND_COMPARISON".equals(workspaceType) ? "基金比较" : "基金分析";
        String name = (definition != null && definition.getName() != null) ? definition.getName() : defaultName;

        return ToolWorkspacePayload.builder()
                .id("workspace-" + UUID.randomUUID().toString().substring(0, 8))
                .type(workspaceType)
                .name(name)
                .referenceId(referenceId)
                .capabilities("FUND_COMPARISON".equals(workspaceType)
                        ? List.of("component_comparison_edit")
                        : List.of("component_analysis_edit"))
                .components(workspaceComponents)
                .build();
    }

    public List<ToolWorkspaceReference> references(ToolWorkspacePayload workspace) {
        if (workspace == null || workspace.components() == null || workspace.components().isEmpty()) {
            return List.of();
        }
        List<ToolWorkspaceReference.Item> items = java.util.stream.IntStream
                .range(0, workspace.components().size())
                .mapToObj(index -> {
                    ToolWorkspaceComponent component = workspace.components().get(index);
                    return ToolWorkspaceReference.Item.builder()
                            .index(index + 1)
                            .name(component.name())
                            .description(component.description())
                            .content(component.content())
                            .build();
                })
                .toList();
        return List.of(ToolWorkspaceReference.builder()
                .id(workspace.referenceId())
                .type("component")
                .items(items)
                .build());
    }

    private ToolWorkspaceComponent toWorkspaceComponent(ToolDefinition definition,
                                                        ToolExecuteRequest request,
                                                        UITreeComponent component) {
        Map<String, Object> params = resolveParams(definition, component, request);
        String instanceId = idFactory.create(component.getId(), params);
        String desc = component.getCardTitle() == null || component.getCardTitle().isBlank()
                ? component.getName()
                : component.getCardTitle();

        return ToolWorkspaceComponent.builder()
                .id(instanceId)
                .type("MCPTool")
                .name(component.getName())
                .description(desc)
                .prompt(desc)
                .config(config(definition, component))
                .params(params)
                .layout(layout(component))
                .status("focused")
                .data(component.getData())
                .chartOption(component.getChartOption())
                .url(component.getUrl())
                .build();
    }

    private Map<String, Object> resolveParams(ToolDefinition definition,
                                              UITreeComponent component,
                                              ToolExecuteRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (request != null && request.getArguments() != null) {
            params.putAll(request.getArguments());
        }
        return params;
    }

    private Map<String, Object> config(ToolDefinition definition, UITreeComponent component) {
        Map<String, Object> config = new LinkedHashMap<>();
        String scope = definition != null && definition.getScope() != null ? definition.getScope() : "TOOL";
        String toolId = definition != null && definition.getId() != null ? definition.getId() : "default";
        config.put("id", scope + "/" + toolId + "/" + component.getId());
        config.put("name", component.getName());
        config.put("description", component.getCardTitle());
        config.put("url", component.getUrl() == null ? "" : component.getUrl());
        config.put("displayType", component.getDisplayType());
        config.put("columns", component.getColumns() == null ? List.of() : component.getColumns());
        if (component.getMetadata() != null) {
            config.put("metadata", component.getMetadata());
        }
        return config;
    }

    private Map<String, Object> layout(UITreeComponent component) {
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("width", component.getWidth() == null ? 24 : component.getWidth());
        return layout;
    }
}
