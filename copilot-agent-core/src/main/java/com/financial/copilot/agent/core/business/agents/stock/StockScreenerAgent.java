package com.financial.copilot.agent.core.business.agents.stock;

import com.financial.copilot.agent.core.business.agents.AgentRole;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.agentscope.RoleToolkitRegistry;
import com.financial.copilot.agent.core.infra.dag.artifact.*;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.tools.stock.StockScreeningTool;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** AgentScope ReAct role that chooses stock screening parameters and tools. */
@Component
public class StockScreenerAgent {
    private final AgentScopeAgentFactory factory;
    private final RoleToolkitRegistry toolkitRegistry;

    @Autowired
    public StockScreenerAgent(AgentScopeAgentFactory factory, RoleToolkitRegistry toolkitRegistry) {
        this.factory = factory;
        this.toolkitRegistry = toolkitRegistry;
    }

    public StockScreenerAgent(AgentScopeAgentFactory factory, StockScreeningTool screening) {
        this(factory, RoleToolkitRegistry.forStockScreener(screening));
    }

    public Artifact<String> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        Toolkit toolkit = toolkitRegistry.createToolkit(AgentRole.STOCK_SCREENER, context);
        if (toolkit == null) {
            toolkit = toolkitRegistry.toolkitFor(AgentRole.STOCK_SCREENER);
        }
        var run = factory.invokeWithTrace(new AgentScopeAgentFactory.AgentDefinition("StockScreenerAgent", "股票多因子筛选",
                "你是股票筛选 ReAct Agent。必须调用 screen_stocks，观察真实结果后结束，禁止编造股票。", toolkit, 5),
                context.request().prompt() + "\n节点参数=" + node.getParams(), context);
        String result = run.requireLastText("screen_stocks");
        return new Artifact<>("art-stock-screen-" + UUID.randomUUID().toString().substring(0, 8), node.getOutputType(),
                node.getNodeId(), result, ArtifactMetadata.standard("StockScreenerAgent"),
                EvidenceContract.sufficient("股票筛选工具已执行", java.util.List.of("stock-screen://result")));
    }
}
