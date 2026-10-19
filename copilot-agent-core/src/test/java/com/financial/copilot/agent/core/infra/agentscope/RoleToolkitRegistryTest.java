package com.financial.copilot.agent.core.infra.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.business.agents.AgentRole;
import com.financial.copilot.agent.core.infra.context.ConversationContextSnapshot;
import com.financial.copilot.agent.core.infra.context.ExecutionIdentity;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactMetadata;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.infra.dag.runtime.RunMode;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.infra.llm.dto.LlmSettingsDTO;
import com.financial.copilot.agent.core.infra.memory.MemoryClient;
import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import com.financial.copilot.agent.tools.fund.FundScreeningTool;
import com.financial.copilot.agent.tools.stock.StockQuantAnalysisTool;
import com.financial.copilot.agent.tools.stock.StockScreeningTool;
import com.financial.copilot.agent.core.infra.mcp.ReactiveMcpClient;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleToolkitRegistryTest {

    private final RoleToolkitRegistry registry = new RoleToolkitRegistry(
            mock(FundQuantAnalysisTool.class),
            mock(FundHoldingsQueryTool.class),
            mock(FundReportRetrieverTool.class),
            mock(FundScreeningTool.class),
            null,
            null,
            mock(StockQuantAnalysisTool.class),
            mock(StockScreeningTool.class),
            new ObjectMapper().findAndRegisterModules()
    );

    @Test
    void registryProvidesToolkitsForAllAgentRoles() {
        for (AgentRole role : AgentRole.values()) {
            Toolkit toolkit = registry.toolkitFor(role);
            assertThat(toolkit).isNotNull();
            assertThat(toolkit.getToolNames()).isNotEmpty();
        }

        assertThat(registry.toolkitFor(AgentRole.FUND_ANALYZER).getToolNames())
                .containsExactlyInAnyOrder("fund_metrics", "fund_holdings", "fund_report");
        assertThat(registry.toolkitFor(AgentRole.REPORT_SYNTHESIZER).getToolNames())
                .containsExactlyInAnyOrder("read_research_artifacts");
    }

    @Test
    void throwsOnNullRole() {
        assertThatThrownBy(() -> registry.toolkitFor(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void concurrentInvocationsObserveIsolatedContextsOnSharedToolkit() {
        Toolkit reportToolkit = registry.toolkitFor(AgentRole.REPORT_SYNTHESIZER);
        Map<String, String> capturedResults = new ConcurrentHashMap<>();

        AgentScopeAgentFactory factory = new AgentScopeAgentFactory(
                () -> LlmSettingsDTO.builder().model("scripted").build(),
                ignored -> new Model() {
                    @Override
                    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        boolean hasResult = messages.stream().anyMatch(m -> m.hasContentBlocks(io.agentscope.core.message.ToolResultBlock.class));
                        if (!hasResult) {
                            return Flux.just(ChatResponse.builder()
                                    .content(List.of(ToolUseBlock.builder().id(UUID.randomUUID().toString()).name("read_research_artifacts").input(Map.of()).build()))
                                    .finishReason("tool_calls")
                                    .build());
                        }
                        return Flux.just(ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text("done").build()))
                                .finishReason("stop")
                                .build());
                    }
                    @Override public String getModelName() { return "scripted"; }
                },
                mock(MemoryClient.class)
        );

        CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
            NodeExecutionContext ctx1 = createContext("wf-1", 101L, "node-A", 1, "art-1-payload");
            var invocation = factory.invokeWithTrace(
                    new AgentScopeAgentFactory.AgentDefinition("ReportSynthesizerAgent", "synth", "prompt", reportToolkit, 3),
                    "prompt 1", ctx1);
            capturedResults.put("wf-1", invocation.requireLastText("read_research_artifacts"));
        });

        CompletableFuture<Void> future2 = CompletableFuture.runAsync(() -> {
            NodeExecutionContext ctx2 = createContext("wf-2", 202L, "node-B", 2, "art-2-payload");
            var invocation = factory.invokeWithTrace(
                    new AgentScopeAgentFactory.AgentDefinition("ReportSynthesizerAgent", "synth", "prompt", reportToolkit, 3),
                    "prompt 2", ctx2);
            capturedResults.put("wf-2", invocation.requireLastText("read_research_artifacts"));
        });

        CompletableFuture.allOf(future1, future2).join();

        assertThat(capturedResults.get("wf-1")).contains("art-1-payload");
        assertThat(capturedResults.get("wf-1")).doesNotContain("art-2-payload");

        assertThat(capturedResults.get("wf-2")).contains("art-2-payload");
        assertThat(capturedResults.get("wf-2")).doesNotContain("art-1-payload");
    }

    @Test
    void mcpEnabledForwardsToolExecution() {
        FundQuantAnalysisTool quant = mock(FundQuantAnalysisTool.class);
        FundHoldingsQueryTool holdings = mock(FundHoldingsQueryTool.class);
        FundReportRetrieverTool reports = mock(FundReportRetrieverTool.class);
        ReactiveMcpClient mcpClient = mock(ReactiveMcpClient.class);
        when(mcpClient.isEnabled()).thenReturn(true);
        when(mcpClient.callToolSync(eq("fund_metrics"), any())).thenReturn("mcp-fund-metrics-result");

        RoleToolkitRegistry.FundAnalyzerTools tools =
                new RoleToolkitRegistry.FundAnalyzerTools(quant, holdings, reports, mcpClient);

        String result = tools.metrics("000001");
        assertThat(result).isEqualTo("mcp-fund-metrics-result");
        verify(mcpClient).callToolSync(eq("fund_metrics"), any());
    }

    @Test
    void mcpFailingFallsBackToLocalTool() {
        FundQuantAnalysisTool quant = mock(FundQuantAnalysisTool.class);
        FundHoldingsQueryTool holdings = mock(FundHoldingsQueryTool.class);
        FundReportRetrieverTool reports = mock(FundReportRetrieverTool.class);
        ReactiveMcpClient mcpClient = mock(ReactiveMcpClient.class);
        when(mcpClient.isEnabled()).thenReturn(true);
        when(mcpClient.callToolSync(eq("fund_metrics"), any()))
                .thenThrow(new IllegalStateException("MCP connection refused"));
        when(quant.getFundMetrics("000001", null, null)).thenReturn("local-fund-metrics-result");

        RoleToolkitRegistry.FundAnalyzerTools tools =
                new RoleToolkitRegistry.FundAnalyzerTools(quant, holdings, reports, mcpClient);

        String result = tools.metrics("000001");
        assertThat(result).isEqualTo("local-fund-metrics-result");
        verify(quant).getFundMetrics("000001", null, null);
    }

    private NodeExecutionContext createContext(String wfId, Long userId, String nodeId, int attempt, String artifactContent) {
        ExecutionIdentity identity = new ExecutionIdentity("DEFAULT", userId, UUID.randomUUID(), wfId, "sess-" + wfId);
        GraphRunRequest request = new GraphRunRequest(
                identity,
                new ConversationContextSnapshot(List.of(), null, "prompt", Instant.now()),
                null,
                false,
                usage -> {},
                RunMode.SYNC
        );
        ArtifactStore store = new ArtifactStore();
        Artifact<String> art = new Artifact<>("art-" + nodeId, ArtifactType.GENERAL, nodeId, artifactContent,
                ArtifactMetadata.standard("test"), null);
        store.store(nodeId, art);
        NodeInput input = new NodeInput(Map.of("in", art), Map.of("in", art));
        return new NodeExecutionContext(request, nodeId, store, new CancellationToken(nodeId), attempt, input);
    }
}
