package com.financial.copilot.agent.core.business.agents;

import com.financial.copilot.agent.core.business.agents.a2a.A2aAgentRegistry;
import com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent;
import com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.infra.dag.runtime.RunMode;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("AgentNodeRouter A2A 动态路由测试")
class AgentNodeRouterTest {

    @Test
    @DisplayName("验证根据 node 参数与 prompt 自动路由至正确的 A2A 智能体")
    void testDynamicRoutingByTaskTypeAndAsset() {
        A2aExecutableAgent fundScreener = mock(A2aExecutableAgent.class);
        when(fundScreener.agentCard()).thenReturn(AgentCardDefinitions.fundScreenerCard());
        when(fundScreener.supports(eq("SCREENING"), eq("FUND"))).thenReturn(true);
        Artifact<?> fundArtifact = Artifact.of("fundPool", ArtifactType.FUND_POOL, "node-1", "fund-data");
        doReturn(fundArtifact).when(fundScreener).execute(any(), any(), any());

        A2aExecutableAgent stockScreener = mock(A2aExecutableAgent.class);
        when(stockScreener.agentCard()).thenReturn(AgentCardDefinitions.stockScreenerCard());
        when(stockScreener.supports(eq("SCREENING"), eq("STOCK"))).thenReturn(true);
        Artifact<?> stockArtifact = Artifact.of("stockPool", ArtifactType.GENERAL, "node-2", "stock-data");
        doReturn(stockArtifact).when(stockScreener).execute(any(), any(), any());

        A2aAgentRegistry registry = new A2aAgentRegistry(List.of(fundScreener, stockScreener));
        AgentNodeRouter router = new AgentNodeRouter(registry);

        GraphRunRequest fundRequest = new GraphRunRequest(
                "r1", 100L, UUID.randomUUID(), 1L, "sess-1",
                "帮我筛选消费基金", false, null, null, RunMode.SYNC
        );
        NodeExecutionContext fundContext = new NodeExecutionContext(
                fundRequest, "node-1", new ArtifactStore(), new CancellationToken("node-1")
        );
        GraphNode fundNode = GraphNode.builder().nodeId("node-1").taskType("SCREENING").params(Map.of("assetCategory", "FUND")).build();

        Artifact<?> fundResult = router.execute(fundNode, NodeInput.empty(), fundContext);
        assertNotNull(fundResult);
        assertEquals("fund-data", fundResult.payload());

        GraphRunRequest stockRequest = new GraphRunRequest(
                "r2", 100L, UUID.randomUUID(), 2L, "sess-2",
                "帮我筛选白酒股票", false, null, null, RunMode.SYNC
        );
        NodeExecutionContext stockContext = new NodeExecutionContext(
                stockRequest, "node-2", new ArtifactStore(), new CancellationToken("node-2")
        );
        GraphNode stockNode = GraphNode.builder().nodeId("node-2").taskType("SCREENING").params(Map.of("assetCategory", "STOCK")).build();

        Artifact<?> stockResult = router.execute(stockNode, NodeInput.empty(), stockContext);
        assertNotNull(stockResult);
        assertEquals("stock-data", stockResult.payload());
    }
}
