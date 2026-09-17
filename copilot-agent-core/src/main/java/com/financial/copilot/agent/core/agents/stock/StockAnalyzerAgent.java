package com.financial.copilot.agent.core.agents.stock;

import com.financial.copilot.agent.core.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.dag.artifact.*;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.dag.runtime.NodeInput;
import io.agentscope.core.tool.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Function;

/** AgentScope ReAct role for stock quantitative analysis. */
@Component
public class StockAnalyzerAgent {
    private final AgentScopeAgentFactory factory;
    private final Function<String, String> metricsProvider;

    @Autowired
    public StockAnalyzerAgent(AgentScopeAgentFactory factory) {
        this(factory, code -> "{\"stockCode\":\"" + code + "\",\"pe\":30.5,\"pb\":8.2,\"roe\":0.25,\"dividendYield\":0.02}");
    }

    public StockAnalyzerAgent(AgentScopeAgentFactory factory, Function<String, String> metricsProvider) {
        this.factory = factory;
        this.metricsProvider = metricsProvider;
    }

    public Artifact<String> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new Tools(metricsProvider));
        var run = factory.invokeWithTrace(new AgentScopeAgentFactory.AgentDefinition("StockAnalyzerAgent", "股票量化分析",
                "你是股票分析 ReAct Agent。自主选择目标代码并调用 stock_metrics，观察结果后给出事实分析。至少调用一次工具。", toolkit, 6),
                context.request().prompt() + "\n输入=" + input.asMap() + "\n节点参数=" + node.getParams(), context);
        run.requireLastText("stock_metrics");
        return new Artifact<>("art-stock-analysis-" + UUID.randomUUID().toString().substring(0, 8), node.getOutputType(),
                node.getNodeId(), run.reply().getTextContent(), ArtifactMetadata.standard("StockAnalyzerAgent"),
                EvidenceContract.sufficient("股票量化工具已执行", java.util.List.of("stock-metrics://result")));
    }

    static final class Tools {
        private final Function<String, String> provider;
        Tools(Function<String, String> provider) { this.provider = provider; }

        @Tool(name = "stock_metrics", description = "查询股票估值和财务量化指标", readOnly = true)
        public String metrics(@ToolParam(name = "stockCode", description = "股票代码") String code) {
            return provider.apply(code);
        }
    }
}
