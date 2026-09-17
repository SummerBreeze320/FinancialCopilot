package com.financial.copilot.agent.core.agents.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.agentscope.AgentScopeInvocation;
import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.artifact.payload.ComparisonReport;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.dag.runtime.RunMode;
import com.financial.copilot.agent.core.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.workspace.ConfiguredToolWorkspacePublisher;
import com.financial.copilot.agent.tools.configured.facade.FundComparisonToolSet;
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

class FundComparatorAgentTest {

    private AgentScopeAgentFactory agentFactory;
    private FundQuantAnalysisTool quantTool;
    private FundHoldingsQueryTool holdingsTool;
    private FundReportRetrieverTool reportTool;
    private FundComparisonToolSet fundComparisonToolSet;
    private ConfiguredToolWorkspacePublisher workspacePublisher;
    private ObjectMapper objectMapper;
    private FundComparatorAgent comparatorAgent;

    @BeforeEach
    void setUp() {
        agentFactory = mock(AgentScopeAgentFactory.class);
        quantTool = mock(FundQuantAnalysisTool.class);
        holdingsTool = mock(FundHoldingsQueryTool.class);
        reportTool = mock(FundReportRetrieverTool.class);
        fundComparisonToolSet = mock(FundComparisonToolSet.class);
        workspacePublisher = mock(ConfiguredToolWorkspacePublisher.class);
        objectMapper = new ObjectMapper();

        comparatorAgent = new FundComparatorAgent(
                agentFactory,
                quantTool,
                holdingsTool,
                reportTool,
                fundComparisonToolSet,
                workspacePublisher,
                objectMapper
        );
    }

    @Test
    void executePublishesWorkspaceAndAppendsEvidence() {
        AgentScopeInvocation invocation = mock(AgentScopeInvocation.class);
        Msg replyMsg = mock(Msg.class);
        when(replyMsg.getTextContent()).thenReturn("两只基金在收益与回撤上各具特色。");
        when(invocation.reply()).thenReturn(replyMsg);
        when(invocation.requireLastText("compare_metrics")).thenReturn("指标对标完成");

        ToolResultBlock block = ToolResultBlock.builder()
                .id("trb-comp-1")
                .name("compare_metrics")
                .state(ToolResultState.SUCCESS)
                .output(List.of(TextBlock.builder().text("ok").build()))
                .build();
        when(invocation.observations()).thenReturn(Map.of("compare_metrics", List.of(block)));

        when(agentFactory.invokeWithTrace(any(), any(), any())).thenAnswer(invocationOnMock -> {
            ConfiguredToolExecutionCollector.record(ToolExecuteResult.builder()
                    .success(true)
                    .textForLlm("text")
                    .rawData(Map.of())
                    .build());
            return invocation;
        });

        when(workspacePublisher.publish(any(), any()))
                .thenReturn(List.of("art-workspace-comp-789"));

        GraphRunRequest request = new GraphRunRequest("run-2", 1L, UUID.randomUUID(), null, "session", "对比基金", false,
                null, ignored -> {}, RunMode.SYNC);
        NodeExecutionContext context = new NodeExecutionContext(request, "compare-node", new ArtifactStore(), new CancellationToken("run-2"));

        GraphNode node = GraphNode.builder()
                .nodeId("compare-node")
                .taskType("COMPARISON")
                .outputType(ArtifactType.COMPARISON_REPORT)
                .params(Map.of("targetCode", "005827.OF"))
                .build();

        NodeInput input = new NodeInput(Map.of(), Map.of());

        Artifact<ComparisonReport> result = comparatorAgent.execute(node, input, context);

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo(ArtifactType.COMPARISON_REPORT);
        assertThat(result.payload().comparedFundCodes()).contains("005827.OF");

        assertThat(result.evidenceContract().evidenceUris()).contains("artifact://art-workspace-comp-789");
        assertThat(result.evidenceContract().evidenceUris()).contains("fund://005827.OF");

        verify(workspacePublisher).publish(eq(context), any());
    }

    @Test
    void executeSucceedsWithConfiguredComparisonToolWithoutLegacyMetrics() {
        AgentScopeInvocation invocation = mock(AgentScopeInvocation.class);
        Msg replyMsg = mock(Msg.class);
        when(replyMsg.getTextContent()).thenReturn("基础信息与仓位配置对标完成。");
        when(invocation.reply()).thenReturn(replyMsg);

        ToolResultBlock block = ToolResultBlock.builder()
                .id("trb-comp-cfg")
                .name("compare_basic_info")
                .state(ToolResultState.SUCCESS)
                .output(List.of(TextBlock.builder().text("basic info compare fact").build()))
                .build();
        // observations has compare_basic_info, NO compare_metrics!
        when(invocation.observations()).thenReturn(Map.of("compare_basic_info", List.of(block)));

        when(agentFactory.invokeWithTrace(any(), any(), any())).thenAnswer(invocationOnMock -> {
            ConfiguredToolExecutionCollector.record(ToolExecuteResult.builder()
                    .success(true)
                    .textForLlm("text")
                    .rawData(Map.of())
                    .build());
            return invocation;
        });

        when(workspacePublisher.publish(any(), any()))
                .thenReturn(List.of("art-workspace-basic-888"));

        GraphRunRequest request = new GraphRunRequest("run-comp-2", 1L, UUID.randomUUID(), null, "session", "对比", false,
                null, ignored -> {}, RunMode.SYNC);
        NodeExecutionContext context = new NodeExecutionContext(request, "compare-node", new ArtifactStore(), new CancellationToken("run-comp-2"));

        GraphNode node = GraphNode.builder()
                .nodeId("compare-node")
                .taskType("COMPARISON")
                .outputType(ArtifactType.COMPARISON_REPORT)
                .params(Map.of("targetCode", "005827.OF"))
                .build();

        NodeInput input = new NodeInput(Map.of(), Map.of());

        Artifact<ComparisonReport> result = comparatorAgent.execute(node, input, context);

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo(ArtifactType.COMPARISON_REPORT);
        assertThat(result.evidenceContract().evidenceUris()).contains("artifact://art-workspace-basic-888");
        assertThat(result.evidenceContract().evidenceUris()).contains("fund://005827.OF");
    }

    @Test
    void throwsWhenNoComparisonEvidenceAtAll() {
        AgentScopeInvocation invocation = mock(AgentScopeInvocation.class);
        when(invocation.observations()).thenReturn(Map.of());

        when(agentFactory.invokeWithTrace(any(), any(), any())).thenReturn(invocation);

        GraphRunRequest request = new GraphRunRequest("run-comp-3", 1L, UUID.randomUUID(), null, "session", "对比", false,
                null, ignored -> {}, RunMode.SYNC);
        NodeExecutionContext context = new NodeExecutionContext(request, "compare-node", new ArtifactStore(), new CancellationToken("run-comp-3"));

        GraphNode node = GraphNode.builder()
                .nodeId("compare-node")
                .taskType("COMPARISON")
                .outputType(ArtifactType.COMPARISON_REPORT)
                .params(Map.of("targetCode", "005827.OF"))
                .build();

        NodeInput input = new NodeInput(Map.of(), Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> comparatorAgent.execute(node, input, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without comparison evidence");
    }
}
