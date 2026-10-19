package com.financial.copilot.agent.core.business.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeInvocation;
import com.financial.copilot.agent.core.infra.agentscope.RoleToolkitRegistry;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.RunMode;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.infra.supervisor.ReviewAction;
import com.financial.copilot.agent.core.infra.supervisor.ReviewDecision;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("ReviewerAgent 审查角色单元测试")
class ReviewerAgentTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Reviewer 正常给出 PASS 裁决")
    void testReviewPass() {
        AgentScopeAgentFactory factory = Mockito.mock(AgentScopeAgentFactory.class);
        RoleToolkitRegistry registry = Mockito.mock(RoleToolkitRegistry.class);
        when(registry.toolkitFor(AgentRole.REVIEWER)).thenReturn(new Toolkit());

        Msg mockReply = Msg.builder().textContent("""
                ```json
                {
                    "action": "PASS",
                    "targetTaskIds": [],
                    "issues": [],
                    "requiredEvidence": [],
                    "confidence": 0.95
                }
                ```
                """).build();
        AgentScopeInvocation invocation = new AgentScopeInvocation(mockReply, Map.of());
        when(factory.invokeWithTrace(any(), any(), any())).thenReturn(invocation);

        ReviewerAgent reviewer = new ReviewerAgent(factory, registry, mapper);

        ExecutionGraph graph = new ExecutionGraph("g1");
        GraphNode n1 = GraphNode.builder().nodeId("n1").name("分析").taskType("BATCH_ANALYSIS").build();
        graph.addNode(n1);

        ArtifactStore store = new ArtifactStore();
        store.store("n1", Artifact.of("a1", ArtifactType.FUND_RESEARCH, "n1", "有效分析结论"));

        GraphRunRequest req = new GraphRunRequest("r1", 1L, UUID.randomUUID(), 0L, "sess", "query", false, null, null, RunMode.SYNC);
        NodeExecutionContext ctx = new NodeExecutionContext(req, "__reviewer__", store, new CancellationToken("r1"));

        ReviewDecision decision = reviewer.review(graph, store, ctx);
        assertThat(decision.action()).isEqualTo(ReviewAction.PASS);
        assertThat(decision.confidence()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("Reviewer 发现数据缺失给出 RETRY_TASK 裁决")
    void testReviewRetryTask() {
        AgentScopeAgentFactory factory = Mockito.mock(AgentScopeAgentFactory.class);
        RoleToolkitRegistry registry = Mockito.mock(RoleToolkitRegistry.class);
        when(registry.toolkitFor(AgentRole.REVIEWER)).thenReturn(new Toolkit());

        Msg mockReply = Msg.builder().textContent("""
                ```json
                {
                    "action": "RETRY_TASK",
                    "targetTaskIds": ["n1"],
                    "issues": ["数据滞后"],
                    "requiredEvidence": ["fund://latest_nav"],
                    "confidence": 0.85
                }
                ```
                """).build();
        AgentScopeInvocation invocation = new AgentScopeInvocation(mockReply, Map.of());
        when(factory.invokeWithTrace(any(), any(), any())).thenReturn(invocation);

        ReviewerAgent reviewer = new ReviewerAgent(factory, registry, mapper);
        ExecutionGraph graph = new ExecutionGraph("g1");
        ArtifactStore store = new ArtifactStore();
        GraphRunRequest req = new GraphRunRequest("r1", 1L, UUID.randomUUID(), 0L, "sess", "query", false, null, null, RunMode.SYNC);
        NodeExecutionContext ctx = new NodeExecutionContext(req, "__reviewer__", store, new CancellationToken("r1"));

        ReviewDecision decision = reviewer.review(graph, store, ctx);
        assertThat(decision.action()).isEqualTo(ReviewAction.RETRY_TASK);
        assertThat(decision.targetTaskIds()).contains("n1");
        assertThat(decision.issues()).contains("数据滞后");
    }
}
