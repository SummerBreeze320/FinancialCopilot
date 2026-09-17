package com.financial.copilot.agent.core.agents.stock;

import com.financial.copilot.agent.core.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.dag.artifact.*;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.dag.runtime.NodeInput;
import com.financial.copilot.agent.tools.stock.StockScreeningTool;
import io.agentscope.core.tool.*;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** AgentScope ReAct role that chooses stock screening parameters and tools. */
@Component
public class StockScreenerAgent {
    private final AgentScopeAgentFactory factory;
    private final StockScreeningTool screening;

    public StockScreenerAgent(AgentScopeAgentFactory factory, StockScreeningTool screening) {
        this.factory = factory;
        this.screening = screening;
    }

    public Artifact<String> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new Tools(screening));
        var run = factory.invokeWithTrace(new AgentScopeAgentFactory.AgentDefinition("StockScreenerAgent", "股票多因子筛选",
                "你是股票筛选 ReAct Agent。必须调用 screen_stocks，观察真实结果后结束，禁止编造股票。", toolkit, 5),
                context.request().prompt() + "\n节点参数=" + node.getParams(), context);
        String result = run.requireLastText("screen_stocks");
        return new Artifact<>("art-stock-screen-" + UUID.randomUUID().toString().substring(0, 8), node.getOutputType(),
                node.getNodeId(), result, ArtifactMetadata.standard("StockScreenerAgent"),
                EvidenceContract.sufficient("股票筛选工具已执行", java.util.List.of("stock-screen://result")));
    }

    static final class Tools {
        private final StockScreeningTool tool;
        Tools(StockScreeningTool tool) { this.tool = tool; }

        @Tool(name = "screen_stocks", description = "按结构化条件筛选真实股票", readOnly = true)
        public String screen(
                @ToolParam(name = "exchange", description = "交易所", required = false) String exchange,
                @ToolParam(name = "sector", description = "行业", required = false) String sector,
                @ToolParam(name = "minMarketCapBillion", description = "最小市值亿元", required = false) Double marketCap,
                @ToolParam(name = "maxPeTtm", description = "最大PE", required = false) Double pe,
                @ToolParam(name = "maxPb", description = "最大PB", required = false) Double pb,
                @ToolParam(name = "minRoe", description = "最小ROE", required = false) Double roe,
                @ToolParam(name = "minDividendYield", description = "最小股息率", required = false) Double dividend,
                @ToolParam(name = "sortBy", description = "排序字段", required = false) String sortBy,
                @ToolParam(name = "sortOrder", description = "排序方向", required = false) String sortOrder,
                @ToolParam(name = "limit", description = "返回数量", required = false) Integer limit) {
            Map<String, Object> map = new HashMap<>();
            if (exchange != null) map.put("exchange", exchange);
            if (sector != null) map.put("sector", sector);
            if (marketCap != null) map.put("marketCap", marketCap);
            if (pe != null) map.put("pe", pe);
            if (limit != null) map.put("limit", limit);
            return tool.screenStocks(map);
        }
    }
}
