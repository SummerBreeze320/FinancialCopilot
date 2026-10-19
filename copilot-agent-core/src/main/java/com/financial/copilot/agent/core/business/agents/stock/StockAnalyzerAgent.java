package com.financial.copilot.agent.core.business.agents.stock;

import com.financial.copilot.agent.core.business.agents.AgentRole;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.agentscope.RoleToolkitRegistry;
import com.financial.copilot.agent.core.infra.dag.artifact.*;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.tools.stock.StockQuantAnalysisTool;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** AgentScope ReAct role for stock quantitative analysis. */
@Component
public class StockAnalyzerAgent {
    private final AgentScopeAgentFactory factory;
    private final RoleToolkitRegistry toolkitRegistry;

    @Autowired
    public StockAnalyzerAgent(AgentScopeAgentFactory factory, RoleToolkitRegistry toolkitRegistry) {
        this.factory = factory;
        this.toolkitRegistry = toolkitRegistry;
    }

    public StockAnalyzerAgent(AgentScopeAgentFactory factory, StockQuantAnalysisTool quant) {
        this(factory, RoleToolkitRegistry.forStockAnalyzer(quant));
    }

    public Artifact<String> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        Toolkit toolkit = toolkitRegistry.createToolkit(AgentRole.STOCK_ANALYZER, context);
        if (toolkit == null) {
            toolkit = toolkitRegistry.toolkitFor(AgentRole.STOCK_ANALYZER);
        }
        var run = factory.invokeWithTrace(new AgentScopeAgentFactory.AgentDefinition("StockAnalyzerAgent", "股票量化分析",
                "你是股票分析 ReAct Agent。自主选择目标代码并调用 stock_metrics，观察结果后给出事实分析。至少调用一次工具。", toolkit, 6),
                context.request().prompt() + "\n输入=" + input.asMap() + "\n节点参数=" + node.getParams(), context);
        run.requireLastText("stock_metrics");
        return new Artifact<>("art-stock-analysis-" + UUID.randomUUID().toString().substring(0, 8), node.getOutputType(),
                node.getNodeId(), run.reply().getTextContent(), ArtifactMetadata.standard("StockAnalyzerAgent"),
                EvidenceContract.sufficient("股票量化工具已执行", java.util.List.of("stock-metrics://result")));
    }
}
