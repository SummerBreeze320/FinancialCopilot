package com.financial.copilot.agent.core.infra.tool.stock;

import com.financial.copilot.agent.core.infra.agent.AgentRole;
import com.financial.copilot.agent.core.infra.tool.ResearchToolContext;
import com.financial.copilot.agent.tools.stock.StockProfileTool;
import com.financial.copilot.agent.tools.stock.StockQuantAnalysisTool;
import com.financial.copilot.agent.tools.stock.StockScreeningTool;
import com.financial.copilot.agent.tools.stock.StockTechnicalAnalysisTool;
import com.financial.copilot.common.stock.dto.StockScreeningCriteria;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * <h1>股票投研智能体工具集 (StockResearchTools)</h1>
 * <p>
 * 面向 {@link AgentRole#STOCK_RESEARCH} 的专业工具适配层。
 * 遵循 Tool-as-Truth 规范，模型仅感知股票代码/简称等业务入参，
 * 会话身份与追踪凭证严格通过隐藏的 {@link ToolContext} 流转并自动沉淀事实证据。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class StockResearchTools {

    private final StockProfileTool profileTool;
    private final StockQuantAnalysisTool quantTool;
    private final Optional<StockScreeningTool> screeningTool;
    private final Optional<StockTechnicalAnalysisTool> technicalTool;

    public StockResearchTools(StockProfileTool profileTool, StockQuantAnalysisTool quantTool) {
        this(profileTool, quantTool, Optional.empty(), Optional.empty());
    }

    @Autowired
    public StockResearchTools(
            StockProfileTool profileTool,
            StockQuantAnalysisTool quantTool,
            @Autowired(required = false) Optional<StockScreeningTool> screeningTool,
            @Autowired(required = false) Optional<StockTechnicalAnalysisTool> technicalTool
    ) {
        this.profileTool = Objects.requireNonNull(profileTool, "profileTool cannot be null");
        this.quantTool = Objects.requireNonNull(quantTool, "quantTool cannot be null");
        this.screeningTool = screeningTool != null ? screeningTool : Optional.empty();
        this.technicalTool = technicalTool != null ? technicalTool : Optional.empty();
    }

    @Tool(description = "查询 A 股股票全维档案与关键量化估值指标（代码、名称、行业板块、总市值、市盈率 PE-TTM、市净率 PB 等）")
    public String getStockProfile(String query, ToolContext context) {
        log.info("[STOCK-TOOL] 查询股票档案: query={}", query);
        String result = profileTool.getStockProfile(query);
        ResearchToolContext.recordEvidence(context, "getStockProfile", "股票档案查询: " + query, result);
        return result;
    }

    @Tool(description = "查询指定 A 股个股的量化与基本面财务指标（ROE、股息率、资产负债等）")
    public String getStockMetrics(String stockCode, ToolContext context) {
        log.info("[STOCK-TOOL] 查询股票基本面量化指标: stockCode={}", stockCode);
        String result = quantTool.getStockMetrics(stockCode);
        ResearchToolContext.recordEvidence(context, "getStockMetrics", "股票基本面指标: " + stockCode, result);
        return result;
    }

    @Tool(description = "根据多因子条件结构化初筛 A 股股票标的池（PE、PB、ROE、市值、行业等）")
    public String screenStocks(StockScreeningCriteria criteria, ToolContext context) {
        log.info("[STOCK-TOOL] 多因子初筛股票: criteria={}", criteria);
        if (screeningTool.isEmpty()) {
            return "[]";
        }
        String result = screeningTool.get().screenStocks(criteria);
        ResearchToolContext.recordEvidence(context, "screenStocks", "多因子股票筛选", result);
        return result;
    }

    @Tool(description = "计算并获取指定 A 股个股全维度技术分析指标（SMA均线、EMA、RSI、MACD与历史年化波动率）")
    public String getTechnicalIndicators(String stockCode, ToolContext context) {
        log.info("[STOCK-TOOL] 计算股票技术指标: stockCode={}", stockCode);
        if (technicalTool.isEmpty()) {
            return "{\"status\": \"UNAVAILABLE\", \"message\": \"技术分析引擎未就绪\"}";
        }
        String result = technicalTool.get().getTechnicalIndicators(stockCode);
        ResearchToolContext.recordEvidence(context, "getTechnicalIndicators", "股票技术分析: " + stockCode, result);
        return result;
    }
}
