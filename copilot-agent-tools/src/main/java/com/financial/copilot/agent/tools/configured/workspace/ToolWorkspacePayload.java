package com.financial.copilot.agent.tools.configured.workspace;

import lombok.Builder;

import java.util.List;

/**
 * 工作台完整交付载荷模型。
 */
@Builder(toBuilder = true)
public record ToolWorkspacePayload(
        String id,
        String type,
        String name,
        int referenceId,
        List<String> capabilities,
        List<ToolWorkspaceComponent> components
) {
    public ToolWorkspacePayload {
        id = id == null ? "" : id;
        type = type == null ? "" : type;
        name = name == null ? "" : name;
        referenceId = Math.max(referenceId, 0);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        components = components == null ? List.of() : List.copyOf(components);
    }
}
