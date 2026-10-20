package com.financial.copilot.gateway.client;

import com.financial.copilot.gateway.credential.GatewayCredentialManager;
import com.financial.copilot.gateway.governance.GatewayAuditSink;
import com.financial.copilot.gateway.governance.GatewayCapacityLimiter;
import com.financial.copilot.gateway.model.LlmGatewayRequest;
import com.financial.copilot.gateway.model.LlmGatewayResponse;
import com.financial.copilot.gateway.model.LlmHealthReport;
import com.financial.copilot.gateway.model.LlmRoutingScene;
import com.financial.copilot.gateway.router.LlmFailoverExecutor;
import com.financial.copilot.gateway.router.LlmRouter;
import io.agentscope.core.model.Model;
import reactor.core.publisher.Mono;

/**
 * <h1>大模型统一网关门面接口 (Unified LLM Gateway Client)</h1>
 * <p>
 * 职责：作为全平台大模型交互的统一单一入口，屏蔽底层厂商凭据管理、
 * 多场景路由调度、并发限流、故障自愈 Failover 与全链路审计。
 * </p>
 *
 * @author FinancialCopilot
 */
public interface LlmGatewayClient {

    /**
     * 响应式执行大模型推理请求（包含并发限流、路由解析、故障自愈与审计追踪）
     *
     * @param request 统一网关请求体
     * @return 响应式网关响应结果
     */
    Mono<LlmGatewayResponse> execute(LlmGatewayRequest request);

    /**
     * 同步阻塞式执行大模型推理请求
     *
     * @param request 统一网关请求体
     * @return 统一网关响应体
     */
    default LlmGatewayResponse executeSync(LlmGatewayRequest request) {
        return execute(request).block();
    }

    /**
     * 为指定业务场景创建适配 AgentScope 运行时的 Model 适配器
     *
     * @param scene          路由场景
     * @param preferredModel 用户或业务指定首选模型 (可为 null)
     * @return 实现了 io.agentscope.core.model.Model 的适配器实例
     */
    Model createAgentScopeModel(LlmRoutingScene scene, String preferredModel);

    /**
     * 便捷方法：为指定业务场景创建 AgentScope Model 适配器（使用场景缺省模型）
     *
     * @param scene 路由场景
     * @return AgentScope Model 适配器
     */
    default Model createAgentScopeModel(LlmRoutingScene scene) {
        return createAgentScopeModel(scene, null);
    }

    /**
     * 获取网关当前运行状况与诊断报告
     *
     * @return 诊断报告
     */
    LlmHealthReport checkHealth();

    /**
     * 获取凭据管理器
     *
     * @return 凭据管理器
     */
    GatewayCredentialManager getCredentialManager();

    /**
     * 获取路由器
     *
     * @return 路由器
     */
    LlmRouter getRouter();

    /**
     * 获取故障转移执行器
     *
     * @return 故障转移执行器
     */
    LlmFailoverExecutor getFailoverExecutor();

    /**
     * 获取并发容量限流器
     *
     * @return 并发容量限流器
     */
    GatewayCapacityLimiter getCapacityLimiter();

    /**
     * 获取审计接收器
     *
     * @return 审计接收器
     */
    GatewayAuditSink getAuditSink();
}
