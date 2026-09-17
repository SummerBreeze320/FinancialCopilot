package com.financial.copilot.agent.tools.workspace;

import lombok.Builder;

import java.util.List;

/**
 * 供 LLM Observation 消费的轻量组件引用索引。
 */
@Builder
public record ToolWorkspaceReference(
        int id,
        String type,
        List<Item> items
) {
    public ToolWorkspaceReference {
        type = type == null ? "component" : type;
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Builder
    public record Item(
            int index,
            String name,
            String content,
            String description
    ) {
        public Item {
            name = name == null ? "" : name;
            content = content == null ? "" : content;
            description = description == null ? "" : description;
        }
    }
}
