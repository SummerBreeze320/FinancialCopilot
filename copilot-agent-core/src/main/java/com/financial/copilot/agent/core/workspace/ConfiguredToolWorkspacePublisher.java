package com.financial.copilot.agent.core.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactMetadata;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.artifact.payload.WorkspaceArtifact;
import com.financial.copilot.agent.core.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.tools.configured.model.ToolExecuteResult;
import com.financial.copilot.agent.tools.configured.workspace.ToolWorkspacePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工作台产物发布器：
 * 1. 向 NodeEventBus 发送 type=workspace SSE 事件
 * 2. 将工作台数据转换为 WorkspaceArtifact 存入 context.artifacts()
 * 3. 返回生成的 artifactId 列表供 EvidenceContract 引用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfiguredToolWorkspacePublisher {

    private final ObjectMapper mapper;

    public List<String> publish(NodeExecutionContext context,
                                List<ToolExecuteResult> results) {
        if (context == null || results == null || results.isEmpty()) {
            return List.of();
        }
        return results.stream()
                .map(ToolExecuteResult::getWorkspacePayload)
                .filter(workspace -> workspace != null && workspace.components() != null && !workspace.components().isEmpty())
                .map(workspace -> publishOne(context, workspace))
                .toList();
    }

    private String publishOne(NodeExecutionContext context,
                              ToolWorkspacePayload workspace) {
        if (context.eventBus() != null) {
            String runId = context.request() != null ? context.request().runId() : "";
            context.eventBus().publishWorkspace(runId, context.nodeId(), workspace);
        }

        String artifactId = "art-workspace-" + UUID.randomUUID().toString().substring(0, 8);
        List<Map<String, Object>> componentsMap = mapper.convertValue(
                workspace.components(),
                new TypeReference<List<Map<String, Object>>>() {}
        );

        WorkspaceArtifact payload = new WorkspaceArtifact(
                workspace.id(),
                workspace.type(),
                workspace.name(),
                workspace.referenceId(),
                workspace.capabilities(),
                componentsMap
        );

        if (context.artifacts() != null) {
            context.artifacts().store(context.nodeId(), Artifact.of(
                    artifactId,
                    ArtifactType.WORKSPACE,
                    context.nodeId(),
                    payload,
                    ArtifactMetadata.standard("ConfiguredToolWorkspacePublisher")
            ));
        }

        log.info("Published workspace {} with artifactId {} for node {}", workspace.id(), artifactId, context.nodeId());
        return artifactId;
    }
}
