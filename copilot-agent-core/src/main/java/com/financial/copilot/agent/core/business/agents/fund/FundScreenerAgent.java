package com.financial.copilot.agent.core.business.agents.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.business.agents.AgentRole;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.agentscope.RoleToolkitRegistry;
import com.financial.copilot.agent.core.infra.dag.artifact.*;
import com.financial.copilot.agent.core.infra.dag.artifact.payload.FundPool;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.tools.fund.FundScreeningTool;
import com.financial.copilot.agent.tools.rag.FinancialSchemaRagTool;
import com.financial.copilot.domain.business.fund.entity.FundInfo;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** AgentScope ReAct role that chooses and executes fund screening tools. */
@Component
public class FundScreenerAgent {
    private static final String SYSTEM_PROMPT = """
            你是基金筛选 ReAct Agent。
            若用户问题中包含行业主题、模糊板块或专业金融指标（如芯片、医疗、夏普比率、卡玛比率），可先调用 match_metrics_and_sectors 或 expand_sector 对齐指标助记码与板块 ID。
            必须调用 screen_funds 获取真实数据，观察结果后再结束。
            将用户条件完整转换成 FundScreeningCriteria。禁止编造基金，最终只简述工具结果。
            """;
    private final AgentScopeAgentFactory agentFactory;
    private final RoleToolkitRegistry toolkitRegistry;
    private final ObjectMapper objectMapper;

    @Autowired
    public FundScreenerAgent(AgentScopeAgentFactory agentFactory,
                             RoleToolkitRegistry toolkitRegistry,
                             ObjectMapper objectMapper) {
        this.agentFactory = agentFactory;
        this.toolkitRegistry = toolkitRegistry;
        this.objectMapper = objectMapper;
    }

    public FundScreenerAgent(AgentScopeAgentFactory agentFactory, FundScreeningTool screeningTool,
                             @Nullable FinancialSchemaRagTool schemaRagTool,
                             ObjectMapper objectMapper) {
        this(agentFactory, RoleToolkitRegistry.forFundScreener(screeningTool, schemaRagTool), objectMapper);
    }

    public FundScreenerAgent(AgentScopeAgentFactory agentFactory, FundScreeningTool screeningTool,
                             ObjectMapper objectMapper) {
        this(agentFactory, screeningTool, null, objectMapper);
    }

    public Artifact<FundPool> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        Toolkit toolkit = toolkitRegistry.createToolkit(AgentRole.FUND_SCREENER, context);
        if (toolkit == null) {
            toolkit = toolkitRegistry.toolkitFor(AgentRole.FUND_SCREENER);
        }
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
}
