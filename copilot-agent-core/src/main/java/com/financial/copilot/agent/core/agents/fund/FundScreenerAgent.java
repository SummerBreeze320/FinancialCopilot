package com.financial.copilot.agent.core.agents.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.dag.artifact.*;
import com.financial.copilot.agent.core.dag.artifact.payload.FundPool;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.dag.runtime.NodeInput;
import com.financial.copilot.agent.tools.fund.FundScreeningTool;
import com.financial.copilot.common.fund.dto.FundScreeningCriteria;
import com.financial.copilot.domain.fund.entity.FundInfo;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** AgentScope ReAct role that chooses and executes fund screening tools. */
@Component
public class FundScreenerAgent {
    private static final String SYSTEM_PROMPT = """
            你是基金筛选 ReAct Agent。必须调用 screen_funds 获取真实数据，观察结果后再结束。
            将用户条件完整转换成 FundScreeningCriteria。禁止编造基金，最终只简述工具结果。
            """;
    private final AgentScopeAgentFactory agentFactory;
    private final FundScreeningTool screeningTool;
    private final ObjectMapper objectMapper;

    public FundScreenerAgent(AgentScopeAgentFactory agentFactory, FundScreeningTool screeningTool,
                             ObjectMapper objectMapper) {
        this.agentFactory = agentFactory;
        this.screeningTool = screeningTool;
        this.objectMapper = objectMapper;
    }

    public Artifact<FundPool> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        ScreeningTools tools = new ScreeningTools(screeningTool);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        String prompt = context.request() == null ? String.valueOf(node.getParams())
                : context.request().prompt() + "\n节点参数=" + node.getParams();
        var invocation = agentFactory.invokeWithTrace(new AgentScopeAgentFactory.AgentDefinition(
                "FundScreenerAgent", "公募基金筛选", SYSTEM_PROMPT, toolkit, 5), prompt, context);
        try {
            List<FundInfo> funds = objectMapper.readerForListOf(FundInfo.class)
                    .readValue(invocation.requireLastText("screen_funds"));
            FundPool pool = FundPool.of(funds, "AgentScope ReAct 基于筛选工具结果生成");
            List<String> evidence = pool.fundCodes().stream().map(code -> "fund://" + code).toList();
            return new Artifact<>("art-screen-" + UUID.randomUUID().toString().substring(0, 8),
                    ArtifactType.FUND_POOL, node.getNodeId(), pool,
                    ArtifactMetadata.standard("FundScreenerAgent"),
                    EvidenceContract.sufficient("基金筛选工具已执行", evidence));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid fund screening tool result", e);
        }
    }

    static final class ScreeningTools {
        private final FundScreeningTool delegate;
        ScreeningTools(FundScreeningTool delegate) { this.delegate = delegate; }

        @Tool(name = "screen_funds", description = "按结构化条件筛选真实公募基金", readOnly = true)
        public String screenFunds(
                @ToolParam(name="fundType",description="基金类型",required=false) String fundType,
                @ToolParam(name="sectorTheme",description="行业主题",required=false) String sectorTheme,
                @ToolParam(name="minScaleInBillion",description="最小规模亿元",required=false) Double minScale,
                @ToolParam(name="maxScaleInBillion",description="最大规模亿元",required=false) Double maxScale,
                @ToolParam(name="maxDrawdown3YLimit",description="三年最大回撤上限",required=false) Double drawdown,
                @ToolParam(name="minSharpe3Y",description="三年最小夏普",required=false) Double sharpe,
                @ToolParam(name="minReturn3Y",description="三年最小回报",required=false) Double minReturn,
                @ToolParam(name="minManagerTenureYears",description="经理最小任期",required=false) Integer tenure,
                @ToolParam(name="sortBy",description="排序指标",required=false) String sortBy,
                @ToolParam(name="sortOrder",description="ASC或DESC",required=false) String sortOrder,
                @ToolParam(name="limit",description="返回数量",required=false) Integer limit) {
            return delegate.screenFunds(new FundScreeningCriteria(fundType,sectorTheme,minScale,maxScale,drawdown,
                    sharpe,minReturn,tenure,sortBy,sortOrder,limit));
        }
    }
}
