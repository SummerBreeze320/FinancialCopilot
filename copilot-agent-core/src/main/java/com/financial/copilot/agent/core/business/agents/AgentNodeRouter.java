package com.financial.copilot.agent.core.business.agents;

import com.financial.copilot.agent.core.business.agents.a2a.A2aAgentRegistry;
import com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent;
import com.financial.copilot.agent.core.business.agents.fund.*;
import com.financial.copilot.agent.core.business.agents.stock.*;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * <h1>A2A 智能体节点调度路由器 (AgentNodeRouter)</h1>
 * <p>
 * 基于 {@link A2aAgentRegistry} 发现并动态分发 DAG 节点至符合 {@link io.a2a.spec.AgentCard} 规范的智能体。
 * 消除传统硬编码 switch-case，提供透明平滑的向下兼容。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class AgentNodeRouter {

    private final A2aAgentRegistry registry;

    @Autowired
    public AgentNodeRouter(A2aAgentRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * 向下兼容旧版构造器，自动将传入的 SubAgent 注册进内部 A2aAgentRegistry
     */
    public AgentNodeRouter(FundScreenerAgent fundScreener, StockScreenerAgent stockScreener,
                           FundAnalyzerAgent fundAnalyzer, StockAnalyzerAgent stockAnalyzer,
                           FundComparatorAgent fundComparator, StockComparatorAgent stockComparator,
                           AggregatorAgent synthesizer) {
        this(createCompatibilityRegistry(
                fundScreener, stockScreener, fundAnalyzer, stockAnalyzer,
                fundComparator, stockComparator, synthesizer));
    }

    private static A2aAgentRegistry createCompatibilityRegistry(
            FundScreenerAgent fundScreener, StockScreenerAgent stockScreener,
            FundAnalyzerAgent fundAnalyzer, StockAnalyzerAgent stockAnalyzer,
            FundComparatorAgent fundComparator, StockComparatorAgent stockComparator,
            AggregatorAgent synthesizer) {
        List<A2aExecutableAgent> list = new ArrayList<>();
        if (fundScreener != null) list.add(fundScreener);
        if (stockScreener != null) list.add(stockScreener);
        if (fundAnalyzer != null) list.add(fundAnalyzer);
        if (stockAnalyzer != null) list.add(stockAnalyzer);
        if (fundComparator != null) list.add(fundComparator);
        if (stockComparator != null) list.add(stockComparator);
        if (synthesizer instanceof A2aExecutableAgent a2a) {
            list.add(a2a);
        } else if (synthesizer != null) {
            list.add(new AggregatorAdapter(synthesizer));
        }
        return new A2aAgentRegistry(list);
    }

    public Artifact<?> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        if (!AgentRoleCatalog.supports(node.getTaskType())) {
            throw new IllegalArgumentException("No AgentScope role for task type: " + node.getTaskType());
        }

        String assetCategory = resolveAssetCategory(node, context);
        A2aExecutableAgent targetAgent = registry.getRequiredAgent(node.getTaskType(), assetCategory);
        return targetAgent.execute(node, input, context);
    }

    private String resolveAssetCategory(GraphNode node, NodeExecutionContext context) {
        Object paramCategory = node.getParams() != null ? node.getParams().get("assetCategory") : null;
        if (paramCategory != null && !String.valueOf(paramCategory).isBlank()) {
            return String.valueOf(paramCategory).trim().toUpperCase(java.util.Locale.ROOT);
        }
        if (context != null && context.request() != null && context.request().prompt() != null) {
            String prompt = context.request().prompt();
            if (prompt.contains("股票") || prompt.contains("个股")) {
                return "STOCK";
            }
        }
        return "FUND";
    }

    public A2aAgentRegistry getRegistry() {
        return registry;
    }

    private static class AggregatorAdapter implements A2aExecutableAgent {
        private final AggregatorAgent delegate;

        AggregatorAdapter(AggregatorAgent delegate) {
            this.delegate = delegate;
        }

        @Override
        public io.a2a.spec.AgentCard agentCard() {
            return com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions.reportSynthesizerCard();
        }

        @Override
        public Artifact<?> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
            return delegate.execute(node, input, context);
        }
    }
}
