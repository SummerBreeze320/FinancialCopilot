package com.financial.copilot.agent.core.infra.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.business.agents.AgentRole;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import com.financial.copilot.agent.tools.fund.FundScreeningTool;
import com.financial.copilot.agent.tools.graph.FinancialGraphTool;
import com.financial.copilot.agent.tools.rag.FinancialSchemaRagTool;
import com.financial.copilot.agent.tools.stock.StockQuantAnalysisTool;
import com.financial.copilot.agent.tools.stock.StockScreeningTool;
import com.financial.copilot.common.fund.dto.FundScreeningCriteria;
import com.financial.copilot.common.stock.dto.StockScreeningCriteria;
import com.financial.copilot.agent.core.infra.mcp.ReactiveMcpClient;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * <h1>全局静态角色工具包注册中心</h1>
 * 预初始化各个 Agent 角色专属的不可变 {@link Toolkit}，
 * 杜绝运行时动态注册引发的并发污染与锁竞争。
 */
@Component
public class RoleToolkitRegistry {

    private static final Logger log = LoggerFactory.getLogger(RoleToolkitRegistry.class);

    private final Map<AgentRole, Toolkit> toolkits;

    @Autowired
    public RoleToolkitRegistry(FundQuantAnalysisTool fundQuant,
                               FundHoldingsQueryTool fundHoldings,
                               FundReportRetrieverTool fundReports,
                               FundScreeningTool fundScreening,
                               @Nullable FinancialSchemaRagTool schemaRag,
                               @Nullable FinancialGraphTool graph,
                               StockQuantAnalysisTool stockQuant,
                               StockScreeningTool stockScreening,
                               ObjectMapper mapper,
                               @Autowired(required = false) @Nullable ReactiveMcpClient mcpClient) {
        Map<AgentRole, Toolkit> map = new EnumMap<>(AgentRole.class);
        map.put(AgentRole.FUND_ANALYZER, createFundAnalyzerToolkit(fundQuant, fundHoldings, fundReports, mcpClient));
        map.put(AgentRole.FUND_SCREENER, createFundScreenerToolkit(fundScreening, schemaRag, mcpClient));
        map.put(AgentRole.FUND_COMPARATOR, createFundComparatorToolkit(fundQuant, fundHoldings, fundReports, graph, mcpClient));
        map.put(AgentRole.STOCK_ANALYZER, createStockAnalyzerToolkit(stockQuant, mcpClient));
        map.put(AgentRole.STOCK_SCREENER, createStockScreenerToolkit(stockScreening, mcpClient));
        map.put(AgentRole.STOCK_COMPARATOR, createStockComparatorToolkit(stockQuant, mcpClient));
        map.put(AgentRole.REPORT_SYNTHESIZER, createReportSynthesizerToolkit(mapper));
        map.put(AgentRole.REVIEWER, createReportSynthesizerToolkit(mapper));
        this.toolkits = Collections.unmodifiableMap(map);
    }

    public RoleToolkitRegistry(FundQuantAnalysisTool fundQuant,
                               FundHoldingsQueryTool fundHoldings,
                               FundReportRetrieverTool fundReports,
                               FundScreeningTool fundScreening,
                               @Nullable FinancialSchemaRagTool schemaRag,
                               @Nullable FinancialGraphTool graph,
                               StockQuantAnalysisTool stockQuant,
                               StockScreeningTool stockScreening,
                               ObjectMapper mapper) {
        this(fundQuant, fundHoldings, fundReports, fundScreening, schemaRag, graph, stockQuant, stockScreening, mapper, null);
    }

    public RoleToolkitRegistry(Map<AgentRole, Toolkit> customToolkits) {
        this.toolkits = Collections.unmodifiableMap(new EnumMap<>(customToolkits));
    }

    @Autowired(required = false)
    @Nullable
    private ContextualToolkitFactory contextualToolkitFactory;

    public void setContextualToolkitFactory(ContextualToolkitFactory factory) {
        this.contextualToolkitFactory = factory;
    }

    /**
     * 为指定角色和执行上下文动态创建经过白名单过滤的 Toolkit。
     *
     * @param role    角色枚举
     * @param context 执行上下文 (可为 null)
     * @return 定制工具包实例
     */
    public Toolkit createToolkit(AgentRole role, @Nullable NodeExecutionContext context) {
        if (contextualToolkitFactory != null) {
            return contextualToolkitFactory.createToolkit(role, context);
        }
        return toolkitFor(role);
    }

    /**
     * 获取指定角色的不可变全局工具包
     *
     * @param role 角色枚举
     * @return 工具包实例
     */
    public Toolkit toolkitFor(AgentRole role) {
        Objects.requireNonNull(role, "role must not be null");
        Toolkit toolkit = toolkits.get(role);
        if (toolkit == null) {
            throw new IllegalArgumentException("Unsupported role: " + role);
        }
        return toolkit;
    }

    public static RoleToolkitRegistry forFundAnalyzer(FundQuantAnalysisTool q, FundHoldingsQueryTool h, FundReportRetrieverTool r) {
        return new RoleToolkitRegistry(Map.of(AgentRole.FUND_ANALYZER, createFundAnalyzerToolkit(q, h, r, null)));
    }

    public static RoleToolkitRegistry forFundScreener(FundScreeningTool s, @Nullable FinancialSchemaRagTool rag) {
        return new RoleToolkitRegistry(Map.of(AgentRole.FUND_SCREENER, createFundScreenerToolkit(s, rag, null)));
    }

    public static RoleToolkitRegistry forFundComparator(FundQuantAnalysisTool q, FundHoldingsQueryTool h, FundReportRetrieverTool r, @Nullable FinancialGraphTool g) {
        return new RoleToolkitRegistry(Map.of(AgentRole.FUND_COMPARATOR, createFundComparatorToolkit(q, h, r, g, null)));
    }

    public static RoleToolkitRegistry forStockAnalyzer(StockQuantAnalysisTool q) {
        return new RoleToolkitRegistry(Map.of(AgentRole.STOCK_ANALYZER, createStockAnalyzerToolkit(q, null)));
    }

    public static RoleToolkitRegistry forStockScreener(StockScreeningTool s) {
        return new RoleToolkitRegistry(Map.of(AgentRole.STOCK_SCREENER, createStockScreenerToolkit(s, null)));
    }

    public static RoleToolkitRegistry forStockComparator(StockQuantAnalysisTool q) {
        return new RoleToolkitRegistry(Map.of(AgentRole.STOCK_COMPARATOR, createStockComparatorToolkit(q, null)));
    }

    public static RoleToolkitRegistry forReportSynthesizer(ObjectMapper mapper) {
        return new RoleToolkitRegistry(Map.of(AgentRole.REPORT_SYNTHESIZER, createReportSynthesizerToolkit(mapper)));
    }

    public static RoleToolkitRegistry forReviewer(ObjectMapper mapper) {
        return new RoleToolkitRegistry(Map.of(AgentRole.REVIEWER, createReportSynthesizerToolkit(mapper)));
    }

    private static Toolkit createFundAnalyzerToolkit(FundQuantAnalysisTool q, FundHoldingsQueryTool h, FundReportRetrieverTool r, @Nullable ReactiveMcpClient mcpClient) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FundAnalyzerTools(q, h, r, mcpClient));
        return toolkit;
    }

    private static Toolkit createFundScreenerToolkit(FundScreeningTool s, @Nullable FinancialSchemaRagTool rag, @Nullable ReactiveMcpClient mcpClient) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FundScreenerTools(s, mcpClient));
        if (rag != null) {
            toolkit.registerTool(rag);
        }
        return toolkit;
    }

    private static Toolkit createFundComparatorToolkit(FundQuantAnalysisTool q, FundHoldingsQueryTool h, FundReportRetrieverTool r, @Nullable FinancialGraphTool g, @Nullable ReactiveMcpClient mcpClient) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FundComparatorTools(q, h, r, mcpClient));
        if (g != null) {
            toolkit.registerTool(new GraphComparisonTools(g));
        }
        return toolkit;
    }

    private static Toolkit createStockAnalyzerToolkit(StockQuantAnalysisTool q, @Nullable ReactiveMcpClient mcpClient) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new StockAnalyzerTools(q, mcpClient));
        return toolkit;
    }

    private static Toolkit createStockScreenerToolkit(StockScreeningTool s, @Nullable ReactiveMcpClient mcpClient) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new StockScreenerTools(s, mcpClient));
        return toolkit;
    }

    private static Toolkit createStockComparatorToolkit(StockQuantAnalysisTool q, @Nullable ReactiveMcpClient mcpClient) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new StockComparatorTools(q, mcpClient));
        return toolkit;
    }

    private static Toolkit createReportSynthesizerToolkit(ObjectMapper mapper) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ReportSynthesizerTools(mapper));
        return toolkit;
    }

    public static final class FundAnalyzerTools {
        private final FundQuantAnalysisTool quant;
        private final FundHoldingsQueryTool holdings;
        private final FundReportRetrieverTool reports;
        private final ReactiveMcpClient mcpClient;

        public FundAnalyzerTools(FundQuantAnalysisTool quant, FundHoldingsQueryTool holdings, FundReportRetrieverTool reports) {
            this(quant, holdings, reports, null);
        }

        public FundAnalyzerTools(FundQuantAnalysisTool quant, FundHoldingsQueryTool holdings, FundReportRetrieverTool reports, @Nullable ReactiveMcpClient mcpClient) {
            this.quant = quant;
            this.holdings = holdings;
            this.reports = reports;
            this.mcpClient = mcpClient;
        }

        @Tool(name = "fund_metrics", description = "查询基金收益风险量化指标", readOnly = true)
        public String metrics(@ToolParam(name = "fundCode", description = "六位基金代码") String code) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    return mcpClient.callToolSync("fund_metrics", Map.of("fundCode", code));
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] fund_metrics fallback to local tool: code={}, error={}", code, e.getMessage());
                }
            }
            return quant.getFundMetrics(code, null, null);
        }

        @Tool(name = "fund_holdings", description = "查询基金主要持仓", readOnly = true)
        public String holdings(@ToolParam(name = "fundCode", description = "六位基金代码") String code) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    return mcpClient.callToolSync("fund_holdings", Map.of("fundCode", code));
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] fund_holdings fallback to local tool: code={}, error={}", code, e.getMessage());
                }
            }
            return holdings.getTopHoldings(code, null);
        }

        @Tool(name = "fund_report", description = "查询基金最新季报观点", readOnly = true)
        public String report(@ToolParam(name = "fundCode", description = "六位基金代码") String code) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    return mcpClient.callToolSync("fund_report", Map.of("fundCode", code));
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] fund_report fallback to local tool: code={}, error={}", code, e.getMessage());
                }
            }
            return reports.getLatestQuarterlyReportView(code);
        }
    }

    public static final class FundScreenerTools {
        private final FundScreeningTool delegate;
        private final ReactiveMcpClient mcpClient;

        public FundScreenerTools(FundScreeningTool delegate) {
            this(delegate, null);
        }

        public FundScreenerTools(FundScreeningTool delegate, @Nullable ReactiveMcpClient mcpClient) {
            this.delegate = delegate;
            this.mcpClient = mcpClient;
        }

        @Tool(name = "screen_funds", description = "按结构化条件筛选真实公募基金", readOnly = true)
        public String screenFunds(
                @ToolParam(name = "fundType", description = "基金类型", required = false) String fundType,
                @ToolParam(name = "sectorTheme", description = "行业主题", required = false) String sectorTheme,
                @ToolParam(name = "minScaleInBillion", description = "最小规模亿元", required = false) Double minScale,
                @ToolParam(name = "maxScaleInBillion", description = "最大规模亿元", required = false) Double maxScale,
                @ToolParam(name = "maxDrawdown3YLimit", description = "三年最大回撤上限", required = false) Double drawdown,
                @ToolParam(name = "minSharpe3Y", description = "三年最小夏普", required = false) Double sharpe,
                @ToolParam(name = "minReturn3Y", description = "三年最小回报", required = false) Double minReturn,
                @ToolParam(name = "minManagerTenureYears", description = "经理最小任期", required = false) Integer tenure,
                @ToolParam(name = "sortBy", description = "排序指标", required = false) String sortBy,
                @ToolParam(name = "sortOrder", description = "ASC或DESC", required = false) String sortOrder,
                @ToolParam(name = "limit", description = "返回数量", required = false) Integer limit) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    Map<String, Object> args = new LinkedHashMap<>();
                    if (fundType != null) args.put("fundType", fundType);
                    if (sectorTheme != null) args.put("sectorTheme", sectorTheme);
                    if (minScale != null) args.put("minScaleInBillion", minScale);
                    if (maxScale != null) args.put("maxScaleInBillion", maxScale);
                    if (drawdown != null) args.put("maxDrawdown3YLimit", drawdown);
                    if (sharpe != null) args.put("minSharpe3Y", sharpe);
                    if (minReturn != null) args.put("minReturn3Y", minReturn);
                    if (tenure != null) args.put("minManagerTenureYears", tenure);
                    if (sortBy != null) args.put("sortBy", sortBy);
                    if (sortOrder != null) args.put("sortOrder", sortOrder);
                    if (limit != null) args.put("limit", limit);
                    return mcpClient.callToolSync("screen_funds", args);
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] screen_funds fallback to local tool: error={}", e.getMessage());
                }
            }
            return delegate.screenFunds(new FundScreeningCriteria(
                    fundType, sectorTheme, minScale, maxScale, drawdown, sharpe, minReturn, tenure, sortBy, sortOrder, limit));
        }
    }

    public static final class FundComparatorTools {
        private final FundQuantAnalysisTool q;
        private final FundHoldingsQueryTool h;
        private final FundReportRetrieverTool r;
        private final ReactiveMcpClient mcpClient;

        public FundComparatorTools(FundQuantAnalysisTool q, FundHoldingsQueryTool h, FundReportRetrieverTool r) {
            this(q, h, r, null);
        }

        public FundComparatorTools(FundQuantAnalysisTool q, FundHoldingsQueryTool h, FundReportRetrieverTool r, @Nullable ReactiveMcpClient mcpClient) {
            this.q = q;
            this.h = h;
            this.r = r;
            this.mcpClient = mcpClient;
        }

        @Tool(name = "compare_metrics", description = "对称查询两只基金量化指标；单标的时两个代码相同", readOnly = true)
        public String metrics(@ToolParam(name = "code_a", description = "基金A") String a,
                              @ToolParam(name = "code_b", description = "基金B") String b) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    String resA = mcpClient.callToolSync("fund_metrics", Map.of("fundCode", a));
                    String resB = mcpClient.callToolSync("fund_metrics", Map.of("fundCode", b));
                    return "A=" + resA + "\nB=" + resB;
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] compare_metrics fallback to local tool: error={}", e.getMessage());
                }
            }
            return "A=" + q.getFundMetrics(a, null, null) + "\nB=" + q.getFundMetrics(b, null, null);
        }

        @Tool(name = "compare_holdings", description = "对称查询两只基金持仓", readOnly = true)
        public String holdings(@ToolParam(name = "code_a", description = "基金A") String a,
                               @ToolParam(name = "code_b", description = "基金B") String b) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    String resA = mcpClient.callToolSync("fund_holdings", Map.of("fundCode", a));
                    String resB = mcpClient.callToolSync("fund_holdings", Map.of("fundCode", b));
                    return "A=" + resA + "\nB=" + resB;
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] compare_holdings fallback to local tool: error={}", e.getMessage());
                }
            }
            return "A=" + h.getTopHoldings(a, null) + "\nB=" + h.getTopHoldings(b, null);
        }

        @Tool(name = "compare_reports", description = "对称查询两只基金季报", readOnly = true)
        public String reports(@ToolParam(name = "code_a", description = "基金A") String a,
                              @ToolParam(name = "code_b", description = "基金B") String b) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    String resA = mcpClient.callToolSync("fund_report", Map.of("fundCode", a));
                    String resB = mcpClient.callToolSync("fund_report", Map.of("fundCode", b));
                    return "A=" + resA + "\nB=" + resB;
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] compare_reports fallback to local tool: error={}", e.getMessage());
                }
            }
            return "A=" + r.getLatestQuarterlyReportView(a) + "\nB=" + r.getLatestQuarterlyReportView(b);
        }
    }

    public static final class GraphComparisonTools {
        private final FinancialGraphTool graph;

        public GraphComparisonTools(FinancialGraphTool graph) {
            this.graph = graph;
        }

        @Tool(name = "shared_holdings", description = "查询两只基金的重合持仓", readOnly = true)
        public String shared(@ToolParam(name = "code_a", description = "基金A") String a,
                             @ToolParam(name = "code_b", description = "基金B") String b) {
            return graph.getSharedHoldings(a, b);
        }
    }

    public static final class StockAnalyzerTools {
        private final StockQuantAnalysisTool tool;
        private final ReactiveMcpClient mcpClient;

        public StockAnalyzerTools(StockQuantAnalysisTool tool) {
            this(tool, null);
        }

        public StockAnalyzerTools(StockQuantAnalysisTool tool, @Nullable ReactiveMcpClient mcpClient) {
            this.tool = tool;
            this.mcpClient = mcpClient;
        }

        @Tool(name = "stock_metrics", description = "查询股票估值和财务量化指标", readOnly = true)
        public String metrics(@ToolParam(name = "stockCode", description = "股票代码") String code) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    return mcpClient.callToolSync("stock_metrics", Map.of("stockCode", code));
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] stock_metrics fallback to local tool: code={}, error={}", code, e.getMessage());
                }
            }
            return tool.getStockMetrics(code);
        }
    }

    public static final class StockScreenerTools {
        private final StockScreeningTool tool;
        private final ReactiveMcpClient mcpClient;

        public StockScreenerTools(StockScreeningTool tool) {
            this(tool, null);
        }

        public StockScreenerTools(StockScreeningTool tool, @Nullable ReactiveMcpClient mcpClient) {
            this.tool = tool;
            this.mcpClient = mcpClient;
        }

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
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    Map<String, Object> args = new LinkedHashMap<>();
                    if (exchange != null) args.put("exchange", exchange);
                    if (sector != null) args.put("sector", sector);
                    if (marketCap != null) args.put("minMarketCapBillion", marketCap);
                    if (pe != null) args.put("maxPeTtm", pe);
                    if (pb != null) args.put("maxPb", pb);
                    if (roe != null) args.put("minRoe", roe);
                    if (dividend != null) args.put("minDividendYield", dividend);
                    if (sortBy != null) args.put("sortBy", sortBy);
                    if (sortOrder != null) args.put("sortOrder", sortOrder);
                    if (limit != null) args.put("limit", limit);
                    return mcpClient.callToolSync("screen_stocks", args);
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] screen_stocks fallback to local tool: error={}", e.getMessage());
                }
            }
            return tool.screenStocks(new StockScreeningCriteria(
                    exchange, sector, marketCap, pe, pb, roe, dividend, sortBy, sortOrder, limit));
        }
    }

    public static final class StockComparatorTools {
        private final StockQuantAnalysisTool tool;
        private final ReactiveMcpClient mcpClient;

        public StockComparatorTools(StockQuantAnalysisTool tool) {
            this(tool, null);
        }

        public StockComparatorTools(StockQuantAnalysisTool tool, @Nullable ReactiveMcpClient mcpClient) {
            this.tool = tool;
            this.mcpClient = mcpClient;
        }

        @Tool(name = "compare_stock_metrics", description = "对称查询两只股票的估值和财务量化指标", readOnly = true)
        public String compare(
                @ToolParam(name = "code_a", description = "股票A代码") String codeA,
                @ToolParam(name = "code_b", description = "股票B代码") String codeB) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    String resA = mcpClient.callToolSync("stock_metrics", Map.of("stockCode", codeA));
                    String resB = mcpClient.callToolSync("stock_metrics", Map.of("stockCode", codeB));
                    return "A=" + resA + "\nB=" + resB;
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] compare_stock_metrics fallback to local tool: error={}", e.getMessage());
                }
            }
            return "A=" + tool.getStockMetrics(codeA) + "\nB=" + tool.getStockMetrics(codeB);
        }
    }

    public static final class ReportSynthesizerTools {
        private final ObjectMapper mapper;

        public ReportSynthesizerTools(ObjectMapper mapper) {
            this.mapper = Objects.requireNonNull(mapper).copy().findAndRegisterModules();
        }

        @Tool(name = "read_research_artifacts", description = "读取当前节点绑定的全部强类型研究产物", readOnly = true)
        public String read(NodeExecutionContext context) {
            try {
                if (context != null && context.input() != null && context.input().artifacts() != null && !context.input().artifacts().isEmpty()) {
                    return mapper.writeValueAsString(context.input().artifacts());
                }
                if (context != null && context.artifacts() != null) {
                    return mapper.writeValueAsString(context.artifacts().getAllArtifacts());
                }
                return "{}";
            } catch (Exception e) {
                throw new IllegalStateException("Failed to serialize research artifacts", e);
            }
        }
    }
}
