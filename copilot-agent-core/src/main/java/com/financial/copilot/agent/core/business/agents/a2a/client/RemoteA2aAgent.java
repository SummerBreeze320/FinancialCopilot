package com.financial.copilot.agent.core.business.agents.a2a.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aJsonRpcRequest;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aJsonRpcResponse;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import io.a2a.spec.AgentCard;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * <h1>远程 A2A 外部智能体适配器 (RemoteA2aAgent)</h1>
 * <p>
 * 将外部网络运行的标准 A2A 协议智能体透明映射接入本地 DAG 执行引擎与智能体自发现注册中心。
 * 运行时与 {@link AgentScopeAgentFactory} 统一部署与交互。
 * </p>
 *
 * @author FinancialCopilot
 */
public class RemoteA2aAgent implements A2aExecutableAgent {

    private final AgentCard card;
    private final String baseUrl;
    private final WebClient webClient;
    private final ObjectMapper mapper;

    public RemoteA2aAgent(AgentCard card, String baseUrl, WebClient webClient, ObjectMapper mapper) {
        this.card = Objects.requireNonNull(card, "card");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.webClient = webClient != null ? webClient : WebClient.builder().build();
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    @Override
    public AgentCard agentCard() {
        return card;
    }

    @Override
    public Artifact<?> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        String prompt = context.request() != null ? context.request().prompt() : "";
        A2aJsonRpcRequest request = new A2aJsonRpcRequest(
                "2.0",
                UUID.randomUUID().toString(),
                "tasks.create",
                Map.of("prompt", prompt, "params", node.getParams() != null ? node.getParams() : Map.of())
        );

        String targetUrl = baseUrl.endsWith("/") ? baseUrl + "rpc" : baseUrl + "/rpc";

        A2aJsonRpcResponse response = webClient.post()
                .uri(targetUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(A2aJsonRpcResponse.class)
                .timeout(Duration.ofSeconds(30))
                .block();

        if (response == null || response.error() != null) {
            String errorMsg = (response != null && response.error() != null)
                    ? response.error().message()
                    : "Null response from remote agent";
            throw new IllegalStateException("Remote A2A Agent call failed: " + errorMsg);
        }

        return Artifact.of(
                "remote-art-" + UUID.randomUUID().toString().substring(0, 8),
                ArtifactType.GENERAL,
                node.getNodeId(),
                response.result()
        );
    }
}
