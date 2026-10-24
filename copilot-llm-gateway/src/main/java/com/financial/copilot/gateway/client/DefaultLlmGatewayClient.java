package com.financial.copilot.gateway.client;

import com.financial.copilot.gateway.adapter.GatewayAgentScopeModelAdapter;
import com.financial.copilot.gateway.credential.GatewayCredentialManager;
import com.financial.copilot.gateway.governance.GatewayAuditSink;
import com.financial.copilot.gateway.governance.GatewayCapacityLimiter;
import com.financial.copilot.gateway.model.LlmGatewayRequest;
import com.financial.copilot.gateway.model.LlmGatewayResponse;
import com.financial.copilot.gateway.model.LlmHealthReport;
import com.financial.copilot.gateway.model.LlmRoutingScene;
import com.financial.copilot.gateway.router.LlmFailoverExecutor;
import com.financial.copilot.gateway.router.LlmRouter;
import com.financial.copilot.gateway.router.ProviderEndpointSpec;
import io.agentscope.core.model.Model;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * <h1>大模型统一网关门面默认实现 (Default LLM Gateway Client)</h1>
 * <p>
 * 职责：整合凭据管理、路由规则、Failover 故障自愈、响应式并发限流与审计监控，
 * 为业务层和 AgentScope 运行时提供生产级高可靠大模型访问能力。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Getter
@Component
public class DefaultLlmGatewayClient implements LlmGatewayClient {

    private final GatewayCredentialManager credentialManager;
    private final LlmRouter router;
    private final LlmFailoverExecutor failoverExecutor;
    private final GatewayCapacityLimiter capacityLimiter;
    private final GatewayAuditSink auditSink;
    private final Function<ProviderEndpointSpec, Model> agentScopeDelegateFactory;

    /**
     * 生产环境注入构造器
     *
     * @param credentialManager 凭据管理器
     * @param router            路由器
     * @param failoverExecutor  故障转移执行器
     * @param capacityLimiter   并发限流器
     * @param auditSink         审计接收器
     */
    @org.springframework.beans.factory.annotation.Autowired
    public DefaultLlmGatewayClient(
            GatewayCredentialManager credentialManager,
            LlmRouter router,
            LlmFailoverExecutor failoverExecutor,
            GatewayCapacityLimiter capacityLimiter,
            GatewayAuditSink auditSink
    ) {
        this(credentialManager, router, failoverExecutor, capacityLimiter, auditSink, null);
    }

    /**
     * 可扩展/测试构造器
     *
     * @param credentialManager          凭据管理器
     * @param router                     路由器
     * @param failoverExecutor           故障转移执行器
     * @param capacityLimiter            并发限流器
     * @param auditSink                  审计接收器
     * @param agentScopeDelegateFactory AgentScope 下层委托工厂（用于单元测试 Mock）
     */
    public DefaultLlmGatewayClient(
            GatewayCredentialManager credentialManager,
            LlmRouter router,
            LlmFailoverExecutor failoverExecutor,
            GatewayCapacityLimiter capacityLimiter,
            GatewayAuditSink auditSink,
            Function<ProviderEndpointSpec, Model> agentScopeDelegateFactory
    ) {
        this.credentialManager = Objects.requireNonNull(credentialManager, "credentialManager must not be null");
        this.router = Objects.requireNonNull(router, "router must not be null");
        this.failoverExecutor = Objects.requireNonNull(failoverExecutor, "failoverExecutor must not be null");
        this.capacityLimiter = Objects.requireNonNull(capacityLimiter, "capacityLimiter must not be null");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink must not be null");
        this.agentScopeDelegateFactory = agentScopeDelegateFactory;
    }

    @Override
    public Mono<LlmGatewayResponse> execute(LlmGatewayRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        LlmRoutingScene scene = request.scene() != null ? request.scene() : LlmRoutingScene.STANDARD;
        List<ProviderEndpointSpec> routeChain = router.resolveRoute(scene, request.preferredModel());
        return failoverExecutor.executeWithFailover(request, routeChain);
    }

    @Override
    public Model createAgentScopeModel(LlmRoutingScene scene, String preferredModel) {
        LlmRoutingScene targetScene = scene != null ? scene : LlmRoutingScene.FAST;
        if (agentScopeDelegateFactory != null) {
            return new GatewayAgentScopeModelAdapter(
                    targetScene,
                    preferredModel,
                    router,
                    capacityLimiter,
                    auditSink,
                    agentScopeDelegateFactory
            );
        }
        return new GatewayAgentScopeModelAdapter(
                targetScene,
                preferredModel,
                router,
                capacityLimiter,
                auditSink
        );
    }

    @Override
    public LlmHealthReport checkHealth() {
        Map<String, Boolean> details = new HashMap<>();
        details.put("codex-bridge", true);

        return LlmHealthReport.builder()
                .healthy(true)
                .latencyMs(0L)
                .details(details)
                .reportTime(Instant.now())
                .build();
    }
}
