package com.financial.copilot.agent.core.infra.supervisor.tool.fund;

import com.financial.copilot.agent.core.infra.supervisor.agent.AgentRole;
import com.financial.copilot.agent.core.infra.supervisor.tool.ResearchToolContext;
import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundProfileTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundScreeningTool;
import com.financial.copilot.common.fund.dto.FundScreeningCriteria;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;
import java.util.Optional;

/**
 * <h1>基金投研智能体工具集 (FundResearchTools)</h1>
 * <p>
 * 面向 {@link AgentRole#FUND_RESEARCH} 的专业工具适配层。
 * 遵循 Tool-as-Truth 规范，模型仅感知业务筛选与分析入参，
 * 会话身份与追踪凭证严格通过隐藏的 {@link ToolContext} 流转并自动沉淀事实证据。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundResearchTools {

    private final FundProfileTool profileTool;
    private final FundQuantAnalysisTool quantTool;
    private final FundScreeningTool screeningTool;
    private final FundHoldingsQueryTool holdingsTool;
    private final Optional<FundReportRetrieverTool> reportRetrieverTool;

    public FundResearchTools(
            FundProfileTool profileTool,
            FundQuantAnalysisTool quantTool,
            FundScreeningTool screeningTool,
            FundHoldingsQueryTool holdingsTool
    ) {
        this(profileTool, quantTool, screeningTool, holdingsTool, Optional.empty());
    }

    @Autowired
    public FundResearchTools(
            FundProfileTool profileTool,
            FundQuantAnalysisTool quantTool,
            FundScreeningTool screeningTool,
            FundHoldingsQueryTool holdingsTool,
            @Autowired(required = false) Optional<FundReportRetrieverTool> reportRetrieverTool
    ) {
        this.profileTool = Objects.requireNonNull(profileTool, "profileTool cannot be null");
        this.quantTool = Objects.requireNonNull(quantTool, "quantTool cannot be null");
        this.screeningTool = Objects.requireNonNull(screeningTool, "screeningTool cannot be null");
        this.holdingsTool = Objects.requireNonNull(holdingsTool, "holdingsTool cannot be null");
        this.reportRetrieverTool = reportRetrieverTool != null ? reportRetrieverTool : Optional.empty();
    }

    @Tool(description = "查询公募基金全维档案信息，包括基金全称、类型、管理机构及跟踪基准")
    public String getFundProfile(String query, ToolContext context) {
        log.info("[FUND-TOOL] 查询基金档案: query={}", query);
        String result = profileTool.getFundProfile(query);
        ResearchToolContext.recordEvidence(context, "getFundProfile", "基金档案查询: " + query, result);
        return result;
    }

    @Tool(description = "获取单只基金在指定统计区间的各项权威量化指标（收益率、最大回撤、夏普比率、卡玛比率与持仓集中度）")
    public String getFundMetrics(String fundCode, String startDate, String endDate, ToolContext context) {
        log.info("[FUND-TOOL] 查询基金量化指标: fundCode={}, start={}, end={}", fundCode, startDate, endDate);
        String result = quantTool.getFundMetrics(fundCode, startDate, endDate);
        ResearchToolContext.recordEvidence(context, "getFundMetrics", "基金量化指标: " + fundCode, result);
        return result;
    }

    @Tool(description = "根据多维结构化筛选条件初筛匹配的公募基金池标的")
    public String screenFunds(FundScreeningCriteria criteria, ToolContext context) {
        log.info("[FUND-TOOL] 执行基金多维筛选: criteria={}", criteria);
        String result = screeningTool.screenFunds(criteria);
        ResearchToolContext.recordEvidence(context, "screenFunds", "基金条件筛选", result);
        return result;
    }

    @Tool(description = "查询公募基金指定报告期的前十大重仓股票与行业配置穿透明细")
    public String getTopHoldings(String fundCode, String reportQuarter, ToolContext context) {
        log.info("[FUND-TOOL] 查询基金持仓穿透: fundCode={}, quarter={}", fundCode, reportQuarter);
        String result = holdingsTool.getTopHoldings(fundCode, reportQuarter);
        ResearchToolContext.recordEvidence(context, "getTopHoldings", "基金重仓股穿透: " + fundCode, result);
        return result;
    }

    @Tool(description = "查询公募基金各报告期基金经理的定性投资策略观点与市场展望全文")
    public String getQuarterlyReportViews(String fundCode, ToolContext context) {
        log.info("[FUND-TOOL] 查询基金定性季报策略观点: fundCode={}", fundCode);
        if (reportRetrieverTool.isEmpty()) {
            return "暂无定性季报策略观点。";
        }
        String result = reportRetrieverTool.get().getLatestQuarterlyReportView(fundCode);
        ResearchToolContext.recordEvidence(context, "getQuarterlyReportViews", "基金季报策略观点: " + fundCode, result);
        return result;
    }
}
