package com.financial.copilot.agent.core.infra.supervisor.tool.market;

import com.financial.copilot.agent.core.infra.supervisor.agent.AgentRole;
import com.financial.copilot.agent.core.infra.supervisor.tool.ResearchToolContext;
import com.financial.copilot.agent.tools.graph.FinancialGraphTool;
import com.financial.copilot.agent.tools.rag.FinancialSchemaRagTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * <h1>市场与宏观智能体工具集 (MarketResearchTools)</h1>
 * <p>
 * 面向 {@link AgentRole#MARKET_RESEARCH} 的专业工具适配层。
 * 遵循 Tool-as-Truth 规范，支持指标释义检索、板块三路混合召回、图谱持仓交叉对比等。
 * 会话身份与追踪凭证严格通过隐藏的 {@link ToolContext} 流转并自动沉淀事实证据。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class MarketResearchTools {

    private final FinancialSchemaRagTool ragTool;
    private final Optional<FinancialGraphTool> graphTool;

    public MarketResearchTools(FinancialSchemaRagTool ragTool, Optional<FinancialGraphTool> graphTool) {
        this.ragTool = Objects.requireNonNull(ragTool, "ragTool cannot be null");
        this.graphTool = Objects.requireNonNull(graphTool, "graphTool cannot be null");
    }

    @Tool(description = "基于向量与词面混合召回，根据用户自然语言查询匹配金融指标助记码与分类板块")
    public String matchMetricsAndSectors(String userQuery, Integer topK, ToolContext context) {
        log.info("[MARKET-TOOL] 指标与板块召回: query={}, topK={}", userQuery, topK);
        String result = ragTool.matchMetricsAndSectors(userQuery, topK != null ? topK : 5);
        ResearchToolContext.recordEvidence(context, "matchMetricsAndSectors", "指标与板块知识库召回: " + userQuery, result);
        return result;
    }

    @Tool(description = "根据金融指标助记码查询其精确业务定义、计算口径与推荐关联指标")
    public String explainMetric(String metricCode, ToolContext context) {
        log.info("[MARKET-TOOL] 指标释义查询: metricCode={}", metricCode);
        String result = ragTool.explainMetric(metricCode);
        ResearchToolContext.recordEvidence(context, "explainMetric", "指标口径释义: " + metricCode, result);
        return result;
    }

    @Tool(description = "基于金融知识图谱查询两只公募基金的重仓股票交集与重合度")
    public String getSharedHoldings(String fundCodeA, String fundCodeB, ToolContext context) {
        log.info("[MARKET-TOOL] 双基金重合持仓查询: fundA={}, fundB={}", fundCodeA, fundCodeB);
        if (graphTool.isEmpty()) {
            return "{\"status\": \"UNAVAILABLE\", \"message\": \"知识图谱服务未启用\"}";
        }
        String result = graphTool.get().getSharedHoldings(fundCodeA, fundCodeB);
        ResearchToolContext.recordEvidence(context, "getSharedHoldings", "双基金重合持仓: " + fundCodeA + " vs " + fundCodeB, result);
        return result;
    }

    @Tool(description = "基于金融知识图谱穿透重仓持有指定股票的所有核心公募基金")
    public String getFundsHoldingStock(String stockCode, ToolContext context) {
        log.info("[MARKET-TOOL] 股票重仓基金穿透: stockCode={}", stockCode);
        if (graphTool.isEmpty()) {
            return "{\"status\": \"UNAVAILABLE\", \"message\": \"知识图谱服务未启用\"}";
        }
        String result = graphTool.get().getFundsHoldingStock(stockCode);
        ResearchToolContext.recordEvidence(context, "getFundsHoldingStock", "股票重仓基金穿透: " + stockCode, result);
        return result;
    }
}
