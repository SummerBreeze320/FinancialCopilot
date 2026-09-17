package com.financial.copilot.agent.core.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.artifact.payload.WorkspaceArtifact;
import com.financial.copilot.agent.core.dag.event.NodeEventBus;
import com.financial.copilot.agent.core.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.dag.runtime.RunMode;
import com.financial.copilot.agent.core.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.tools.configured.model.ToolExecuteResult;
import com.financial.copilot.agent.tools.configured.workspace.ToolWorkspaceComponent;
import com.financial.copilot.agent.tools.configured.workspace.ToolWorkspacePayload;
import com.financial.copilot.common.event.ResearchStreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("工作台产物发布器测试")
class ConfiguredToolWorkspacePublisherTest {

    @Test
    @DisplayName("验证发布工作台并落盘 WORKSPACE 产物与广播 SSE 事件")
    void testPublishWorkspaceAndStoreArtifact() {
        ObjectMapper mapper = new ObjectMapper();
        ConfiguredToolWorkspacePublisher publisher = new ConfiguredToolWorkspacePublisher(mapper);

        ArtifactStore store = new ArtifactStore();
        CancellationToken token = new CancellationToken("test-run");
        NodeEventBus eventBus = new NodeEventBus(token);

        GraphRunRequest request = new GraphRunRequest(
                "run-123",
                1L,
                UUID.randomUUID(),
                100L,
                "session-1",
                "基金对比",
                false,
                null,
                null,
                RunMode.SYNC
        );

        NodeExecutionContext context = new NodeExecutionContext(request, "compare-node", store, token, eventBus);

        ToolWorkspaceComponent comp = ToolWorkspaceComponent.builder()
                .id("FundInfoForDefault:005827.OF:2026-09-15")
                .name("基金资料")
                .data(List.of(Map.of("windCode", "005827.OF", "name", "易方达蓝筹精选")))
                .build();

        ToolWorkspacePayload payload = ToolWorkspacePayload.builder()
                .id("workspace-12345678")
                .type("FUND_COMPARISON")
                .name("基金比较")
                .referenceId(1)
                .capabilities(List.of("component_comparison_edit"))
                .components(List.of(comp))
                .build();

        ToolExecuteResult result = ToolExecuteResult.builder()
                .success(true)
                .textForLlm("执行完成")
                .workspacePayload(payload)
                .build();

        List<String> artifactIds = publisher.publish(context, List.of(result));

        assertThat(artifactIds).hasSize(1);
        Artifact<WorkspaceArtifact> stored = store.get("compare-node");
        assertThat(stored).isNotNull();
        assertThat(stored.type()).isEqualTo(ArtifactType.WORKSPACE);
        assertThat(stored.payload().name()).isEqualTo("基金比较");
        assertThat(stored.payload().components()).hasSize(1);
    }
}
