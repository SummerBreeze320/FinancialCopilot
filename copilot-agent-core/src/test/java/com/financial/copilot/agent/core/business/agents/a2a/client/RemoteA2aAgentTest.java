package com.financial.copilot.agent.core.business.agents.a2a.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aJsonRpcResponse;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.core.infra.dag.runtime.RunMode;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RemoteA2aAgent 远程客户端适配器测试")
class RemoteA2aAgentTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("验证将外部 A2A 服务的 JSON-RPC 调用透明映射为本地 Artifact")
    void testRemoteExecution() throws Exception {
        A2aJsonRpcResponse mockResponse = A2aJsonRpcResponse.success("rpc-1", Map.of("data", "remote-stock-analysis-report"));
        String mockResponseBody = mapper.writeValueAsString(mockResponse);

        WebClient webClient = WebClient.builder()
                .exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(mockResponseBody)
                        .build()))
                .build();

        RemoteA2aAgent remoteAgent = new RemoteA2aAgent(
                AgentCardDefinitions.stockAnalyzerCard(),
                "http://localhost:8080/api/v1/a2a/agents/StockAnalyzerAgent",
                webClient,
                mapper
        );

        GraphRunRequest request = new GraphRunRequest("r1", 1L, UUID.randomUUID(), 1L, "s1", "贵州茅台深度分析", false, null, null, RunMode.SYNC);
        NodeExecutionContext context = new NodeExecutionContext(request, "node-stock", new ArtifactStore(), new CancellationToken("node-stock"));
        GraphNode node = GraphNode.builder().nodeId("node-stock").taskType("BATCH_ANALYSIS").params(Map.of("assetCategory", "STOCK")).build();

        Artifact<?> result = remoteAgent.execute(node, NodeInput.empty(), context);
        assertNotNull(result);
        assertTrue(result.payload().toString().contains("remote-stock-analysis-report"));
        assertEquals("StockAnalyzerAgent", remoteAgent.agentCard().name());
    }

    @Test
    @DisplayName("远程服务返回错误时应抛出 IllegalStateException")
    void testRemoteExecutionError() throws Exception {
        A2aJsonRpcResponse mockResponse = A2aJsonRpcResponse.error("rpc-1", -32000, "Remote Agent Internal Error");
        String mockResponseBody = mapper.writeValueAsString(mockResponse);

        WebClient webClient = WebClient.builder()
                .exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(mockResponseBody)
                        .build()))
                .build();

        RemoteA2aAgent remoteAgent = new RemoteA2aAgent(
                AgentCardDefinitions.stockAnalyzerCard(),
                "http://localhost:8080/api/v1/a2a/agents/StockAnalyzerAgent",
                webClient,
                mapper
        );

        GraphRunRequest request = new GraphRunRequest("r1", 1L, UUID.randomUUID(), 1L, "s1", "贵州茅台深度分析", false, null, null, RunMode.SYNC);
        NodeExecutionContext context = new NodeExecutionContext(request, "node-stock", new ArtifactStore(), new CancellationToken("node-stock"));
        GraphNode node = GraphNode.builder().nodeId("node-stock").taskType("BATCH_ANALYSIS").params(Map.of("assetCategory", "STOCK")).build();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> remoteAgent.execute(node, NodeInput.empty(), context));
        assertTrue(ex.getMessage().contains("Remote Agent Internal Error"));
    }
}
