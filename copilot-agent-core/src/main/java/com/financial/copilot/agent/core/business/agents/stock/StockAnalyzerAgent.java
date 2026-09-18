package com.financial.copilot.agent.core.business.agents.stock;

import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.dag.artifact.*;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.tools.stock.StockQuantAnalysisTool;
import io.agentscope.core.tool.*;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** AgentScope ReAct role for stock quantitative analysis. */
@Component
public class StockAnalyzerAgent {
    private final AgentScopeAgentFactory factory; private final StockQuantAnalysisTool quant;
    public StockAnalyzerAgent(AgentScopeAgentFactory factory, StockQuantAnalysisTool quant){this.factory=factory;this.quant=quant;}
    public Artifact<String> execute(GraphNode node, NodeInput input, NodeExecutionContext context){
        Toolkit toolkit=new Toolkit(); toolkit.registerTool(new Tools(quant));
        var run=factory.invokeWithTrace(new AgentScopeAgentFactory.AgentDefinition("StockAnalyzerAgent","股票量化分析",
                "你是股票分析 ReAct Agent。自主选择目标代码并调用 stock_metrics，观察结果后给出事实分析。至少调用一次工具。",toolkit,6),
                context.request().prompt()+"\n输入="+input.asMap()+"\n节点参数="+node.getParams(),context);
        run.requireLastText("stock_metrics");
        return new Artifact<>("art-stock-analysis-"+ UUID.randomUUID().toString().substring(0,8),node.getOutputType(),
                node.getNodeId(),run.reply().getTextContent(),ArtifactMetadata.standard("StockAnalyzerAgent"),
                EvidenceContract.sufficient("股票量化工具已执行",java.util.List.of("stock-metrics://result")));
    }
    static final class Tools{private final StockQuantAnalysisTool tool;Tools(StockQuantAnalysisTool tool){this.tool=tool;}
        @Tool(name="stock_metrics",description="查询股票估值和财务量化指标",readOnly=true)
        public String metrics(@ToolParam(name="stockCode",description="股票代码")String code){return tool.getStockMetrics(code);}}
}
