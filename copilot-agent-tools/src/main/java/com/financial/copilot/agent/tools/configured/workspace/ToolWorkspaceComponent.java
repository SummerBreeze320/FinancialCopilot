package com.financial.copilot.agent.tools.configured.workspace;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * 工作台单组件定义规范模型。
 */
@Builder(toBuilder = true)
public record ToolWorkspaceComponent(
        String id,
        String type,
        String nodeID,
        String name,
        String description,
        String prompt,
        String content,
        Map<String, Object> config,
        Map<String, Object> params,
        Map<String, Object> layout,
        String status,
        List<Map<String, Object>> data,
        Map<String, Object> chartOption,
        String url
) {
    public ToolWorkspaceComponent {
        id = id == null ? "" : id;
        type = type == null ? "MCPTool" : type;
        nodeID = nodeID == null ? "" : nodeID;
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        prompt = prompt == null ? description : prompt;
        content = content == null ? "" : content;
        config = config == null ? Map.of() : Map.copyOf(config);
        params = params == null ? Map.of() : Map.copyOf(params);
        layout = layout == null ? Map.of() : Map.copyOf(layout);
        status = status == null ? "" : status;
        data = data == null ? List.of() : List.copyOf(data);
        chartOption = chartOption == null ? Map.of() : Map.copyOf(chartOption);
        url = url == null ? "" : url;
    }
}
