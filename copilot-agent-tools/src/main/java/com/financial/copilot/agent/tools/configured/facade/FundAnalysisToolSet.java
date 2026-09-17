package com.financial.copilot.agent.tools.configured.facade;

import com.financial.copilot.agent.tools.configured.model.ToolExecuteRequest;
import com.financial.copilot.agent.tools.configured.model.ToolExecuteResult;
import com.financial.copilot.agent.tools.configured.model.ToolSourceMode;
import com.financial.copilot.agent.tools.configured.router.ToolExecutorRouter;
import com.financial.copilot.agent.tools.configured.runtime.ConfiguredToolExecutionCollector;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 基金深度分析与画像工具集 (基于 AgentScope @Tool 注册)。
 * <p>
 * 专供 FundAnalyzerAgent 注册并注入 Toolkit，用于基金全景深度分析与画像：
 * 涵盖全景点评、基础资料、公募F9、经理公司背景、持仓穿透、历年运作指标、收益走势、市场展望、资产配置、市值风格、多因子归因、高频仓位及选股择时能力。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FundAnalysisToolSet {

    private final ToolExecutorRouter router;

    /**
     * 基金全景结构化投研点评分析。
     */
    @Tool(name = "fund_analysis_review", description = "获取基金的全景结构化投研研报摘要(定位、收益特征、投资风格、持仓特征及潜在风险)", readOnly = true)
    public String analysisReview(
            @ToolParam(name = "fundCode", description = "六位公募基金代码，例如 005827") String fundCode) {
        return executeSingle("fund_analysis_review", fundCode, Map.of());
    }

    /**
     * 基金基础信息与申赎规则分析。
     */
    @Tool(name = "fund_analysis_profile", description = "获取基金的基础信息、投资目标、购买费率及赎回规则", readOnly = true)
    public String analysisProfile(
            @ToolParam(name = "fundCode", description = "六位公募基金代码，例如 005827") String fundCode) {
        return executeSingle("fund_analysis_profile", fundCode, Map.of());
    }

    /**
     * 基金公募 F9 全景简报分析。
     */
    @Tool(name = "fund_analysis_factsheet_f9", description = "获取基金的公募 F9 深度资料卡与全景简报视图", readOnly = true)
    public String analysisFactsheetF9(
            @ToolParam(name = "fundCode", description = "六位公募基金代码，例如 005827") String fundCode) {
        return executeSingle("fund_analysis_factsheet_f9", fundCode, Map.of());
    }

    /**
     * 基金经理与所属公司画像分析。
     */
    @Tool(name = "fund_analysis_manager_company", description = "分析基金的现任基金经理背景、所属基金公司概况、产品结构及旗下经理团队", readOnly = true)
    public String analysisManagerCompany(
            @ToolParam(name = "fundCode", description = "六位公募基金代码，例如 005827") String fundCode) {
        return executeSingle("fund_analysis_manager_company", fundCode, Map.of());
    }

    /**
     * 基金底层持仓穿透分析。
     */
    @Tool(name = "fund_analysis_holdings", description = "穿透获取基金的底层持仓明细，包含前十大重仓股票、重仓债券、重仓基金", readOnly = true)
    public String analysisHoldings(
            @ToolParam(name = "fundCode", description = "六位公募基金代码，例如 005827") String fundCode,
            @ToolParam(name = "reportDate", description = "报告期 YYYYMMDD，可选", required = false) String reportDate) {
        Map<String, Object> extra = new HashMap<>();
        if (reportDate != null) extra.put("reportDate", reportDate);
        return executeSingle("fund_analysis_holdings_penetration", fundCode, extra);
    }

    /**
     * 基金历年运行指标变动追踪分析。
     */
    @Tool(name = "fund_analysis_run_indicators", description = "追踪基金历年规模变动、久期/平均剩余期限、剩余期限分布、杠杆率与偏离度等运作指标", readOnly = true)
    public String analysisRunIndicators(
            @ToolParam(name = "fundCode", description = "六位公募基金代码，例如 005827") String fundCode) {
        return executeSingle("fund_analysis_run_indicators", fundCode, Map.of());
    }

    /**
     * 基金收益表现与净值走势分析。
     */
    @Tool(name = "fund_analysis_yield_performance", description = "获取基金的七日年化收益率、万份收益走势、阶段业绩表现表及分年度回报", readOnly = true)
    public String analysisYieldPerformance(
            @ToolParam(name = "fundCode", description = "六位公募基金代码，例如 005827") String fundCode,
            @ToolParam(name = "startDate", description = "起始日期 YYYY-MM-DD，可选", required = false) String startDate,
            @ToolParam(name = "endDate", description = "截止日期 YYYY-MM-DD，可选", required = false) String endDate) {
        Map<String, Object> extra = new HashMap<>();
        if (startDate != null) extra.put("startDate", startDate);
        if (endDate != null) extra.put("endDate", endDate);
        return executeSingle("fund_analysis_yield_performance", fundCode, extra);
    }

    /**
     * 基金定期报告市场展望分析。
     */
    @Tool(name = "fund_analysis_market_outlook", description = "提取基金定期报告中基金经理撰写的宏观经济研判与证券市场展望原文", readOnly = true)
    public String analysisMarketOutlook(
            @ToolParam(name = "fundCode", description = "六位公募基金代码，例如 005827") String fundCode) {
        return executeSingle("fund_analysis_market_outlook", fundCode, Map.of());
    }

    /**
     * 基金大类与债券资产配置分析。
     */
    @Tool(name = "fund_analysis_asset_allocation", description = "分析基金的历年大类资产配置比例与细分债券券种配置结构", readOnly = true)
    public String analysisAssetAllocation(
            @ToolParam(name = "fundCode", description = "六位公募基金代码，例如 005827") String fundCode,
            @ToolParam(name = "reportDate", description = "报告期 YYYYMMDD，可选", required = false) String reportDate) {
        Map<String, Object> extra = new HashMap<>();
        if (reportDate != null) extra.put("reportDate", reportDate);
        return executeSingle("fund_analysis_asset_allocation", fundCode, extra);
    }

    /**
     * 基金市值风格暴露分析。
     */
    @Tool(name = "fund_analysis_style", description = "量化分析基金市值风格暴露(大盘价值/成长、小盘价值/成长)及风格拟合优度 R²", readOnly = true)
    public String analysisStyle(
            @ToolParam(name = "fundCode", description = "六位公募基金代码，例如 005827") String fundCode,
            @ToolParam(name = "startDate", description = "起始日期 YYYY-MM-DD，可选", required = false) String startDate,
            @ToolParam(name = "endDate", description = "截止日期 YYYY-MM-DD，可选", required = false) String endDate) {
        Map<String, Object> extra = new HashMap<>();
        if (startDate != null) extra.put("startDate", startDate);
        if (endDate != null) extra.put("endDate", endDate);
        return executeSingle("fund_analysis_style", fundCode, extra);
    }

    /**
     * 基金净值多因子归因分析。
     */
    @Tool(name = "fund_analysis_nav_attribution", description = "采用 Barra 多因子模型解构基金净值收益来源，量化市场、规模、价值、盈利、动量因子贡献及特质 Alpha", readOnly = true)
    public String analysisNavAttribution(
            @ToolParam(name = "fundCode", description = "六位公募基金代码，例如 005827") String fundCode,
            @ToolParam(name = "startDate", description = "起始日期 YYYY-MM-DD，可选", required = false) String startDate,
            @ToolParam(name = "endDate", description = "截止日期 YYYY-MM-DD，可选", required = false) String endDate) {
        Map<String, Object> extra = new HashMap<>();
        if (startDate != null) extra.put("startDate", startDate);
        if (endDate != null) extra.put("endDate", endDate);
        return executeSingle("fund_analysis_nav_attribution", fundCode, extra);
    }

    /**
     * 基金高频仓位估算分析。
     */
    @Tool(name = "fund_analysis_position", description = "基于高频净值与市场回归测算基金最新的股票仓位、债券仓位及近期仓位变动", readOnly = true)
    public String analysisPosition(
            @ToolParam(name = "fundCode", description = "六位公募基金代码，例如 005827") String fundCode) {
        return executeSingle("fund_analysis_position", fundCode, Map.of());
    }

    /**
     * 基金选股与择时能力评估分析。
     */
    @Tool(name = "fund_analysis_timing", description = "基于 T-M / H-M 模型量化评估基金经理的主动选股能力与市场择时能力得分及同类排名分位数", readOnly = true)
    public String analysisTiming(
            @ToolParam(name = "fundCode", description = "六位公募基金代码，例如 005827") String fundCode,
            @ToolParam(name = "year", description = "回溯年限，如 3 表示近三年", required = false) Integer year) {
        Map<String, Object> extra = new HashMap<>();
        if (year != null) extra.put("year", year);
        return executeSingle("fund_analysis_timing", fundCode, extra);
    }

    private String executeSingle(String toolId, String fundCode, Map<String, Object> extra) {
        Map<String, Object> args = new HashMap<>(extra);
        args.put("windCodes", List.of(normalizeCode(fundCode)));
        ToolExecuteRequest request = ToolExecuteRequest.builder()
                .toolId(toolId)
                .arguments(args)
                .sourceMode(ToolSourceMode.EXTERNAL_CONFIGURED)
                .build();
        ToolExecuteResult result = router.routeAndExecute(request);
        ConfiguredToolExecutionCollector.record(result);
        return result.getTextForLlm();
    }

    private String normalizeCode(String code) {
        if (code == null) return "";
        code = code.trim();
        if (code.length() == 6 && !code.contains(".")) {
            if (code.startsWith("5") || code.startsWith("6")) return code + ".SH";
            return code + ".OF";
        }
        return code;
    }
}
