package com.financial.copilot.agent.core.agents.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.agentscope.AgentScopeInvocation;
import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.artifact.payload.FundPool;
import com.financial.copilot.agent.core.dag.artifact.payload.FundResearchResult;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.dag.runtime.RunMode;
import com.financial.copilot.agent.core.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.workspace.ConfiguredToolWorkspacePublisher;
import com.financial.copilot.agent.tools.configured.facade.FundAnalysisToolSet;
import com.financial.copilot.agent.tools.configured.model.ToolExecuteResult;
import com.financial.copilot.agent.tools.configured.runtime.ConfiguredToolExecutionCollector;
import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FundAnalyzerAgentTest {

    private AgentScopeAgentFactory agentFactory;
    private FundQuantAnalysisTool quantTool;
    private FundHoldingsQueryTool holdingsTool;
    private FundReportRetrieverTool reportTool;
    private FundAnalysisToolSet fundAnalysisToolSet;
    private ConfiguredToolWorkspacePublisher workspacePublisher;
    private ObjectMapper objectMapper;
    private FundAnalyzerAgent analyzerAgent;

    @BeforeEach
    void setUp() {
        agentFactory = mock(AgentScopeAgentFactory.class);
        quantTool = mock(FundQuantAnalysisTool.class);
        holdingsTool = mock(FundHoldingsQueryTool.class);
        reportTool = mock(FundReportRetrieverTool.class);
        fundAnalysisToolSet = mock(FundAnalysisToolSet.class);
        workspacePublisher = mock(ConfiguredToolWorkspacePublisher.class);
        objectMapper = new ObjectMapper();

        analyzerAgent = new FundAnalyzerAgent(
                agentFactory,
                quantTool,
                holdingsTool,
                reportTool,
                fundAnalysisToolSet,
                workspacePublisher,
                objectMapper
        );
    }

    @Test
    void executePublishesWorkspaceAndAppendsEvidence() {
        // Prepare AgentScopeInvocation mock
        AgentScopeInvocation invocation = mock(AgentScopeInvocation.class);
        Msg replyMsg = mock(Msg.class);
        when(replyMsg.getTextContent()).thenReturn("""
                {
                  "evaluatedFunds": [
                    {"fundCode": "005827.OF", "score": 88.5, "summary": "表现优异"}
                  ],
                  "topCandidates": ["005827.OF"]
                }
                """);
        when(invocation.reply()).thenReturn(replyMsg);
        ToolResultBlock block = ToolResultBlock.builder()
                .id("trb-1")
                .name("fund_metrics")
                .state(ToolResultState.SUCCESS)
                .output(List.of(TextBlock.builder().text("ok").build()))
                .build();
        when(invocation.observations()).thenReturn(Map.of("fund_metrics", List.of(block)));

        // Record a tool result to ThreadLocal during agentFactory.invokeWithTrace
        when(agentFactory.invokeWithTrace(any(), any(), any())).thenAnswer(invocationOnMock -> {
            ConfiguredToolExecutionCollector.record(ToolExecuteResult.builder()
                    .success(true)
                    .textForLlm("text")
                    .rawData(Map.of())
                    .build());
            return invocation;
        });

        when(workspacePublisher.publish(any(), any()))
                .thenReturn(List.of("art-workspace-test-123"));

        GraphRunRequest request = new GraphRunRequest("run-1", 1L, UUID.randomUUID(), null, "session", "分析005827", false,
                null, ignored -> {}, RunMode.SYNC);
        NodeExecutionContext context = new NodeExecutionContext(request, "analysis-node", new ArtifactStore(), new CancellationToken("run-1"));

        GraphNode node = GraphNode.builder()
                .nodeId("analysis-node")
                .taskType("BATCH_ANALYSIS")
                .outputType(ArtifactType.FUND_RESEARCH)
                .build();

        Artifact<FundPool> inputArtifact = Artifact.of("pool-art", ArtifactType.FUND_POOL, "screen",
                FundPool.ofCodes(List.of("005827.OF"), "筛选结果"));
        NodeInput input = new NodeInput(Map.of(), Map.of("screen", inputArtifact));

        Artifact<FundResearchResult> result = analyzerAgent.execute(node, input, context);

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo(ArtifactType.FUND_RESEARCH);
        assertThat(result.payload().evaluatedFunds()).hasSize(1);
        assertThat(result.payload().evaluatedFunds().getFirst().get("fundCode")).isEqualTo("005827.OF");

        // Verify that workspace artifact id is recorded in evidence URIs
        assertThat(result.evidenceContract().evidenceUris()).contains("artifact://art-workspace-test-123");
        assertThat(result.evidenceContract().evidenceUris()).contains("fund://005827.OF");

        verify(workspacePublisher).publish(eq(context), any());
    }

    @Test
    void executeSucceedsWithConfiguredAnalysisToolWithoutLegacyMetrics() {
        AgentScopeInvocation invocation = mock(AgentScopeInvocation.class);
        Msg replyMsg = mock(Msg.class);
        when(replyMsg.getTextContent()).thenReturn("""
                {
                  "evaluatedFunds": [
                    {"fundCode": "005827.OF", "score": 92.0, "summary": "配置化画像深度分析完成"}
                  ],
                  "topCandidates": ["005827.OF"]
                }
                """);
        when(invocation.reply()).thenReturn(replyMsg);
        ToolResultBlock block = ToolResultBlock.builder()
                .id("trb-cfg-1")
                .name("fund_analysis_profile")
                .state(ToolResultState.SUCCESS)
                .output(List.of(TextBlock.builder().text("profile distilled fact").build()))
                .build();
        // observations has fund_analysis_profile, NO fund_metrics!
        when(invocation.observations()).thenReturn(Map.of("fund_analysis_profile", List.of(block)));

        when(agentFactory.invokeWithTrace(any(), any(), any())).thenAnswer(invocationOnMock -> {
            ConfiguredToolExecutionCollector.record(ToolExecuteResult.builder()
                    .success(true)
                    .textForLlm("profile fact")
                    .rawData(Map.of())
                    .build());
            return invocation;
        });

        when(workspacePublisher.publish(any(), any()))
                .thenReturn(List.of("art-workspace-profile-456"));

        GraphRunRequest request = new GraphRunRequest("run-2", 1L, UUID.randomUUID(), null, "session", "分析005827画像", false,
                null, ignored -> {}, RunMode.SYNC);
        NodeExecutionContext context = new NodeExecutionContext(request, "analysis-node", new ArtifactStore(), new CancellationToken("run-2"));

        GraphNode node = GraphNode.builder()
                .nodeId("analysis-node")
                .taskType("BATCH_ANALYSIS")
                .outputType(ArtifactType.FUND_RESEARCH)
                .build();

        Artifact<FundPool> inputArtifact = Artifact.of("pool-art", ArtifactType.FUND_POOL, "screen",
                FundPool.ofCodes(List.of("005827.OF"), "筛选结果"));
        NodeInput input = new NodeInput(Map.of(), Map.of("screen", inputArtifact));

        Artifact<FundResearchResult> result = analyzerAgent.execute(node, input, context);

        assertThat(result).isNotNull();
        assertThat(result.evidenceContract().evidenceUris()).contains("artifact://art-workspace-profile-456");
        assertThat(result.evidenceContract().evidenceUris()).contains("fund://005827.OF");
    }

    @Test
    void throwsWhenNoEvidenceAtAll() {
        AgentScopeInvocation invocation = mock(AgentScopeInvocation.class);
        when(invocation.observations()).thenReturn(Map.of());

        when(agentFactory.invokeWithTrace(any(), any(), any())).thenReturn(invocation);

        GraphRunRequest request = new GraphRunRequest("run-3", 1L, UUID.randomUUID(), null, "session", "分析", false,
                null, ignored -> {}, RunMode.SYNC);
        NodeExecutionContext context = new NodeExecutionContext(request, "analysis-node", new ArtifactStore(), new CancellationToken("run-3"));

        GraphNode node = GraphNode.builder()
                .nodeId("analysis-node")
                .taskType("BATCH_ANALYSIS")
                .outputType(ArtifactType.FUND_RESEARCH)
                .build();

        Artifact<FundPool> inputArtifact = Artifact.of("pool-art", ArtifactType.FUND_POOL, "screen",
                FundPool.ofCodes(List.of("005827.OF"), "筛选结果"));
        NodeInput input = new NodeInput(Map.of(), Map.of("screen", inputArtifact));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> analyzerAgent.execute(node, input, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without tool evidence");
    }
}
