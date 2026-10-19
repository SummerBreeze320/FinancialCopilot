package com.financial.copilot.agent.core.business.agents;

import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeInvocation;
import com.financial.copilot.agent.core.infra.agentscope.RoleToolkitRegistry;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.infra.dag.runtime.RunMode;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.infra.supervisor.ReviewDecision;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("AggregatorAgent / ReportSynthesizerAgent 聚合与降级披露测试")
class ReportSynthesizerAgentTest {

    @Test
    @DisplayName("降级场景下合成报告，包含明确的限制披露与数据缺口")
    void testSynthesizeWithDegradation() {
        AgentScopeAgentFactory factory = Mockito.mock(AgentScopeAgentFactory.class);
        RoleToolkitRegistry registry = Mockito.mock(RoleToolkitRegistry.class);
        when(registry.toolkitFor(AgentRole.REPORT_SYNTHESIZER)).thenReturn(new Toolkit());

        Msg mockReply = Msg.builder().textContent("基于部分事实合成的研报内容").build();
        io.agentscope.core.message.ToolResultBlock toolResult = io.agentscope.core.message.ToolResultBlock.builder()
                .id("obs-1")
                .name("read_research_artifacts")
                .state(io.agentscope.core.message.ToolResultState.SUCCESS)
                .output(List.of(io.agentscope.core.message.TextBlock.builder().text("{}").build()))
                .build();
        AgentScopeInvocation invocation = new AgentScopeInvocation(mockReply, Map.of("read_research_artifacts", List.of(toolResult)));
        when(factory.invokeWithTrace(any(), any(), any())).thenReturn(invocation);

        ReportSynthesizerAgent synthesizer = new ReportSynthesizerAgent(factory, registry);

        GraphNode node = GraphNode.builder().nodeId("synth").name("合成").taskType("SYNTHESIS").build();
        Artifact<String> art = Artifact.of("a1", ArtifactType.FUND_RESEARCH, "n1", "基础收益数据");
        NodeInput input = new NodeInput(Map.of("analysis", art));

        ArtifactStore store = new ArtifactStore();
        store.store("n1", art);
        GraphRunRequest req = new GraphRunRequest("r1", 1L, UUID.randomUUID(), 0L, "sess", "基金收益分析", false, null, null, RunMode.SYNC);
        NodeExecutionContext ctx = new NodeExecutionContext(req, "synth", store, new CancellationToken("r1"), 1, input);

        ReviewDecision degradeDecision = ReviewDecision.degrade("宏观指标缺失", List.of("利率数据未更新"));

        Artifact<FinalSynthesisReport> result = synthesizer.executeWithReview(node, input, ctx, degradeDecision);

        assertThat(result.type()).isEqualTo(ArtifactType.FINAL_REPORT);
        assertThat(result.payload().summary()).contains("降级披露");
        assertThat(result.payload().markdownReport()).contains("基于部分事实合成的研报内容");
    }
}
