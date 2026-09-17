package com.financial.copilot.agent.core.dag.artifact.payload;

import java.util.List;
import java.util.Map;

/**
 * 工作台强类型产物载荷实体 (用于 Artifact 总线与持久化存储)。
 */
public record WorkspaceArtifact(
        String id,
        String type,
        String name,
        int referenceId,
        List<String> capabilities,
        List<Map<String, Object>> components
) {
    public WorkspaceArtifact {
        id = id == null ? "" : id;
        type = type == null ? "" : type;
        name = name == null ? "" : name;
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        components = components == null ? List.of() : List.copyOf(components);
    }
}
