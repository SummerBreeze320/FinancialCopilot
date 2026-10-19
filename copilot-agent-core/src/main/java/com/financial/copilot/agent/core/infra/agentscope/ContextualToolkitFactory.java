package com.financial.copilot.agent.core.infra.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.business.agents.AgentRole;
import com.financial.copilot.agent.core.infra.config.AgentRoleProperties;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.mcp.ReactiveMcpClient;
import com.financial.copilot.agent.core.infra.security.UserToolPermissionService;
import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import com.financial.copilot.agent.tools.fund.FundScreeningTool;
import com.financial.copilot.agent.tools.graph.FinancialGraphTool;
import com.financial.copilot.agent.tools.rag.FinancialSchemaRagTool;
import com.financial.copilot.agent.tools.fund.FundPoolTool;
import com.financial.copilot.agent.tools.stock.StockQuantAnalysisTool;
import com.financial.copilot.agent.tools.stock.StockScreeningTool;
import com.financial.copilot.agent.tools.stock.StockWatchlistTool;
import com.financial.copilot.agent.tools.fund.FundProfileTool;
import com.financial.copilot.agent.tools.stock.StockProfileTool;
import com.financial.copilot.common.fund.dto.FundScreeningCriteria;
import com.financial.copilot.common.intent.IntentType;
import com.financial.copilot.common.stock.dto.StockScreeningCriteria;
import com.financial.copilot.common.tool.HighRiskTool;
import com.financial.copilot.common.tool.ToolConstraint;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>上下文感知与用户白名单感知的动态工具包工厂 (ContextualToolkitFactory)</h1>
 * <p>
 * 职责：
 * 1. 从 {@link AgentRoleProperties} 读取各 Agent 角色的基准工具清单；
 * 2. 结合 {@link UserToolPermissionService} 与当前调用上下文解析用户的工具白名单；
 * 3. 动态计算交集，为当前 Agent 执行构建精简、安全的专属 {@link Toolkit}；
 * 4. 彻底杜绝无权工具在 Prompt 中对大语言模型暴露，防止 403 异常与死循环。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class ContextualToolkitFactory {

    private static final Logger log = LoggerFactory.getLogger(ContextualToolkitFactory.class);

    private final AgentRoleProperties roleProperties;
    private final UserToolPermissionService permissionService;
    private final Map<String, Object> toolProviders = new HashMap<>();
    private final Map<AgentRole, Toolkit> cachedFullToolkits = new ConcurrentHashMap<>();
    private final FundPoolTool fundPoolTool;
    private final StockWatchlistTool stockWatchlistTool;
    private final ReactiveMcpClient mcpClient;

    @Autowired
    public ContextualToolkitFactory(AgentRoleProperties roleProperties,
                                    UserToolPermissionService permissionService,
                                    FundQuantAnalysisTool fundQuant,
                                    FundHoldingsQueryTool fundHoldings,
                                    FundReportRetrieverTool fundReports,
                                    FundScreeningTool fundScreening,
                                    @Nullable FinancialSchemaRagTool schemaRag,
                                    @Nullable FinancialGraphTool graph,
                                    StockQuantAnalysisTool stockQuant,
                                    StockScreeningTool stockScreening,
                                    ObjectMapper mapper,
                                    @Autowired(required = false) @Nullable ReactiveMcpClient mcpClient,
                                    @Autowired(required = false) @Nullable FundPoolTool fundPoolTool,
                                    @Autowired(required = false) @Nullable StockWatchlistTool stockWatchlistTool,
                                    @Autowired(required = false) @Nullable FundProfileTool fundProfileTool,
                                    @Autowired(required = false) @Nullable StockProfileTool stockProfileTool) {
        this.roleProperties = Objects.requireNonNull(roleProperties);
        this.permissionService = Objects.requireNonNull(permissionService);
        this.fundPoolTool = fundPoolTool;
        this.stockWatchlistTool = stockWatchlistTool;
        this.mcpClient = mcpClient;

        // 注册所有单原子工具代理
        registerProvider("fund_metrics", new FundMetricsTool(fundQuant, mcpClient));
        registerProvider("fund_holdings", new FundHoldingsTool(fundHoldings, mcpClient));
        registerProvider("fund_report", new FundReportTool(fundReports, mcpClient));
        registerProvider("screen_funds", new FundScreeningToolDelegate(fundScreening, mcpClient));
        if (schemaRag != null) {
            registerProvider("schema_rag", schemaRag);
        }
        registerProvider("compare_metrics", new FundCompareMetricsTool(fundQuant, mcpClient));
        registerProvider("compare_holdings", new FundCompareHoldingsTool(fundHoldings, mcpClient));
        registerProvider("compare_reports", new FundCompareReportsTool(fundReports, mcpClient));
        if (graph != null) {
            registerProvider("shared_holdings", new GraphComparisonTool(graph));
        }
        registerProvider("stock_metrics", new StockMetricsTool(stockQuant, mcpClient));
        registerProvider("screen_stocks", new StockScreeningToolDelegate(stockScreening, mcpClient));
        registerProvider("compare_stock_metrics", new StockCompareMetricsTool(stockQuant, mcpClient));
        registerProvider("read_research_artifacts", new ReportSynthesizerTool(mapper));
        if (fundProfileTool != null || (mcpClient != null && mcpClient.isEnabled())) {
            registerProvider("query_fund_profile", new FundProfileToolDelegate(fundProfileTool, mcpClient));
        }
        if (stockProfileTool != null || (mcpClient != null && mcpClient.isEnabled())) {
            registerProvider("query_stock_profile", new StockProfileToolDelegate(stockProfileTool, mcpClient));
        }
    }

    public ContextualToolkitFactory(AgentRoleProperties roleProperties,
                                    UserToolPermissionService permissionService,
                                    FundQuantAnalysisTool fundQuant,
                                    FundHoldingsQueryTool fundHoldings,
                                    FundReportRetrieverTool fundReports,
                                    FundScreeningTool fundScreening,
                                    @Nullable FinancialSchemaRagTool schemaRag,
                                    @Nullable FinancialGraphTool graph,
                                    StockQuantAnalysisTool stockQuant,
                                    StockScreeningTool stockScreening,
                                    ObjectMapper mapper,
                                    @Nullable ReactiveMcpClient mcpClient,
                                    @Nullable FundPoolTool fundPoolTool,
                                    @Nullable StockWatchlistTool stockWatchlistTool) {
        this(roleProperties, permissionService, fundQuant, fundHoldings, fundReports, fundScreening,
             schemaRag, graph, stockQuant, stockScreening, mapper, mcpClient, fundPoolTool, stockWatchlistTool, null, null);
    }

    public ContextualToolkitFactory(AgentRoleProperties roleProperties,
                                    UserToolPermissionService permissionService,
                                    FundQuantAnalysisTool fundQuant,
                                    FundHoldingsQueryTool fundHoldings,
                                    FundReportRetrieverTool fundReports,
                                    FundScreeningTool fundScreening,
                                    @Nullable FinancialSchemaRagTool schemaRag,
                                    @Nullable FinancialGraphTool graph,
                                    StockQuantAnalysisTool stockQuant,
                                    StockScreeningTool stockScreening,
                                    ObjectMapper mapper,
                                    @Nullable ReactiveMcpClient mcpClient) {
        this(roleProperties, permissionService, fundQuant, fundHoldings, fundReports, fundScreening,
             schemaRag, graph, stockQuant, stockScreening, mapper, mcpClient, null, null, null, null);
    }

    private void registerProvider(String name, Object provider) {
        if (provider != null) {
            this.toolProviders.put(name, provider);
        }
    }

    /**
     * 为指定角色和执行上下文构建定制的 Toolkit。
     *
     * @param role    Agent 角色
     * @param context 执行上下文 (可为 null)
     * @return 经过白名单过滤后的 Toolkit
     */
    public Toolkit createToolkit(AgentRole role, @Nullable NodeExecutionContext context) {
        return createToolkit(role, context, null);
    }

    /**
     * 为指定角色、执行上下文与意图构建定制且剪枝后的 Toolkit。
     *
     * @param role    Agent 角色
     * @param context 执行上下文 (可为 null)
     * @param intent  当前分类识别意图 (可为 null)
     * @return 经过意图剪枝与白名单过滤后的 Toolkit
     */
    public Toolkit createToolkit(AgentRole role, @Nullable NodeExecutionContext context, @Nullable IntentType intent) {
        Objects.requireNonNull(role, "role must not be null");
        Long userId = extractUserId(context);

        List<String> baseTools = roleProperties.getToolsFor(role);
        Set<String> candidateTools = intent != null
                ? filterToolsByIntent(new LinkedHashSet<>(baseTools), intent)
                : new LinkedHashSet<>(baseTools);

        Set<String> allowedTools = permissionService.getAllowedTools(userId);

        if (intent == null && allowedTools != null && allowedTools.contains("*")) {
            return cachedFullToolkits.computeIfAbsent(role, this::buildFullToolkit);
        }

        Toolkit dynamicToolkit = new Toolkit();
        for (String toolName : candidateTools) {
            if (permissionService.isToolAllowed(userId, toolName)) {
                if ("fund_pool".equals(toolName) && (fundPoolTool != null || (mcpClient != null && mcpClient.isEnabled()))) {
                    dynamicToolkit.registerTool(new FundPoolToolDelegate(fundPoolTool, mcpClient, userId));
                } else if ("stock_watchlist".equals(toolName) && (stockWatchlistTool != null || (mcpClient != null && mcpClient.isEnabled()))) {
                    dynamicToolkit.registerTool(new StockWatchlistToolDelegate(stockWatchlistTool, mcpClient, userId));
                } else {
                    Object provider = toolProviders.get(toolName);
                    if (provider != null) {
                        dynamicToolkit.registerTool(provider);
                    }
                }
            }
        }
        return dynamicToolkit;
    }

    /**
     * 依据意图类型对基准工具进行 RAG 动态剪枝，消除上下文膨胀
     */
    public Set<String> filterToolsByIntent(Set<String> baselineTools, @Nullable IntentType intent) {
        if (intent == null) {
            return baselineTools;
        }
        if (intent == IntentType.OUT_OF_DOMAIN || intent == IntentType.SYSTEM_HELP) {
            return Collections.emptySet();
        }

        Set<String> relevantTools = switch (intent) {
            case FUND_SCREENING -> Set.of("screen_funds", "fund_metrics", "fund_pool", "query_fund_profile");
            case FUND_DIAGNOSIS -> Set.of("fund_metrics", "fund_holdings", "fund_report", "fund_pool", "query_fund_profile");
            case FUND_COMPARISON -> Set.of("compare_metrics", "compare_holdings", "compare_reports", "shared_holdings", "fund_metrics", "query_fund_profile");
            case ASSET_ALLOCATION -> Set.of("screen_funds", "fund_metrics", "screen_stocks", "stock_metrics", "fund_pool", "stock_watchlist", "query_fund_profile", "query_stock_profile");
            case REPORT_SYNTHESIS -> Set.of("read_research_artifacts", "fund_report");
            case STOCK_RESEARCH -> Set.of("stock_metrics", "screen_stocks", "compare_stock_metrics", "stock_watchlist", "query_stock_profile");
            default -> baselineTools;
        };

        Set<String> intersection = new LinkedHashSet<>(baselineTools);
        intersection.retainAll(relevantTools);
        return intersection;
    }

    private Toolkit buildFullToolkit(AgentRole role) {
        List<String> baseTools = roleProperties.getToolsFor(role);
        Toolkit toolkit = new Toolkit();
        for (String toolName : baseTools) {
            if ("fund_pool".equals(toolName) && (fundPoolTool != null || (mcpClient != null && mcpClient.isEnabled()))) {
                toolkit.registerTool(new FundPoolToolDelegate(fundPoolTool, mcpClient, 1L));
            } else if ("stock_watchlist".equals(toolName) && (stockWatchlistTool != null || (mcpClient != null && mcpClient.isEnabled()))) {
                toolkit.registerTool(new StockWatchlistToolDelegate(stockWatchlistTool, mcpClient, 1L));
            } else {
                Object provider = toolProviders.get(toolName);
                if (provider != null) {
                    toolkit.registerTool(provider);
                }
            }
        }
        return toolkit;
    }

    private Long extractUserId(@Nullable NodeExecutionContext context) {
        if (context == null || context.request() == null || context.request().identity() == null) {
            return null;
        }
        return context.request().identity().userId();
    }

    // ==========================================
    // 单原子工具委托类定义 (支持 MCP 与本地平滑降级)
    // ==========================================

    public static final class FundMetricsTool {
        private final FundQuantAnalysisTool quant;
        private final ReactiveMcpClient mcpClient;

        public FundMetricsTool(FundQuantAnalysisTool quant, @Nullable ReactiveMcpClient mcpClient) {
            this.quant = quant;
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
    }

    public static final class FundHoldingsTool {
        private final FundHoldingsQueryTool holdings;
        private final ReactiveMcpClient mcpClient;

        public FundHoldingsTool(FundHoldingsQueryTool holdings, @Nullable ReactiveMcpClient mcpClient) {
            this.holdings = holdings;
            this.mcpClient = mcpClient;
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
    }

    public static final class FundReportTool {
        private final FundReportRetrieverTool reports;
        private final ReactiveMcpClient mcpClient;

        public FundReportTool(FundReportRetrieverTool reports, @Nullable ReactiveMcpClient mcpClient) {
            this.reports = reports;
            this.mcpClient = mcpClient;
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

    public static final class FundScreeningToolDelegate {
        private final FundScreeningTool delegate;
        private final ReactiveMcpClient mcpClient;

        public FundScreeningToolDelegate(FundScreeningTool delegate, @Nullable ReactiveMcpClient mcpClient) {
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

    public static final class FundCompareMetricsTool {
        private final FundQuantAnalysisTool quant;
        private final ReactiveMcpClient mcpClient;

        public FundCompareMetricsTool(FundQuantAnalysisTool quant, @Nullable ReactiveMcpClient mcpClient) {
            this.quant = quant;
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
            return "A=" + quant.getFundMetrics(a, null, null) + "\nB=" + quant.getFundMetrics(b, null, null);
        }
    }

    public static final class FundCompareHoldingsTool {
        private final FundHoldingsQueryTool holdings;
        private final ReactiveMcpClient mcpClient;

        public FundCompareHoldingsTool(FundHoldingsQueryTool holdings, @Nullable ReactiveMcpClient mcpClient) {
            this.holdings = holdings;
            this.mcpClient = mcpClient;
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
            return "A=" + holdings.getTopHoldings(a, null) + "\nB=" + holdings.getTopHoldings(b, null);
        }
    }

    public static final class FundCompareReportsTool {
        private final FundReportRetrieverTool reports;
        private final ReactiveMcpClient mcpClient;

        public FundCompareReportsTool(FundReportRetrieverTool reports, @Nullable ReactiveMcpClient mcpClient) {
            this.reports = reports;
            this.mcpClient = mcpClient;
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
            return "A=" + reports.getLatestQuarterlyReportView(a) + "\nB=" + reports.getLatestQuarterlyReportView(b);
        }
    }

    public static final class GraphComparisonTool {
        private final FinancialGraphTool graph;

        public GraphComparisonTool(FinancialGraphTool graph) {
            this.graph = graph;
        }

        @Tool(name = "shared_holdings", description = "查询两只基金的重合持仓", readOnly = true)
        public String shared(@ToolParam(name = "code_a", description = "基金A") String a,
                             @ToolParam(name = "code_b", description = "基金B") String b) {
            return graph.getSharedHoldings(a, b);
        }
    }

    public static final class StockMetricsTool {
        private final StockQuantAnalysisTool quant;
        private final ReactiveMcpClient mcpClient;

        public StockMetricsTool(StockQuantAnalysisTool quant, @Nullable ReactiveMcpClient mcpClient) {
            this.quant = quant;
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
            return quant.getStockMetrics(code);
        }
    }

    public static final class StockScreeningToolDelegate {
        private final StockScreeningTool tool;
        private final ReactiveMcpClient mcpClient;

        public StockScreeningToolDelegate(StockScreeningTool tool, @Nullable ReactiveMcpClient mcpClient) {
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

    public static final class StockCompareMetricsTool {
        private final StockQuantAnalysisTool tool;
        private final ReactiveMcpClient mcpClient;

        public StockCompareMetricsTool(StockQuantAnalysisTool tool, @Nullable ReactiveMcpClient mcpClient) {
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

    public static final class ReportSynthesizerTool {
        private final ObjectMapper mapper;

        public ReportSynthesizerTool(ObjectMapper mapper) {
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

    public static final class FundPoolToolDelegate {
        private final FundPoolTool tool;
        private final ReactiveMcpClient mcpClient;
        private final Long userId;

        public FundPoolToolDelegate(@Nullable FundPoolTool tool, @Nullable ReactiveMcpClient mcpClient, @Nullable Long userId) {
            this.tool = tool;
            this.mcpClient = mcpClient;
            this.userId = userId;
        }

        public FundPoolToolDelegate(FundPoolTool tool, @Nullable Long userId) {
            this(tool, null, userId);
        }

        @Tool(name = "add_to_fund_pool", description = "将指定基金添加至当前用户的私有基金池")
        public String addFund(
                @ToolConstraint(regexPattern = "^\\d{6}$")
                @ToolParam(name = "fundCode", description = "六位基金代码") String fundCode,
                @ToolParam(name = "fundName", description = "基金名称", required = false) String fundName,
                @ToolParam(name = "poolType", description = "池类别(如 CORE/TACTICAL/OBSERVATION)", required = false) String poolType,
                @ToolParam(name = "targetWeight", description = "目标配置权重(0~1)", required = false) Double targetWeight,
                @ToolParam(name = "reason", description = "入池理由", required = false) String reason,
                @ToolParam(name = "tags", description = "标的标签(逗号分隔)", required = false) String tags) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("fundCode", fundCode);
                    if (fundName != null) args.put("fundName", fundName);
                    if (poolType != null) args.put("poolType", poolType);
                    if (targetWeight != null) args.put("targetWeight", targetWeight);
                    if (reason != null) args.put("reason", reason);
                    if (tags != null) args.put("tags", tags);
                    return mcpClient.callToolSync("add_to_fund_pool", args, userId);
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] add_to_fund_pool fallback to local tool: fundCode={}, userId={}, error={}", fundCode, userId, e.getMessage());
                }
            }
            if (tool != null) {
                return tool.addFund(userId, fundCode, fundName, poolType, targetWeight, reason, tags);
            }
            return "{\"error\":\"FundPoolTool is not available locally\"}";
        }

        @Tool(name = "update_fund_pool", description = "更新用户基金池中某标的的目标仓位或入池理由")
        public String updateFund(
                @ToolConstraint(regexPattern = "^\\d{6}$")
                @ToolParam(name = "fundCode", description = "六位基金代码") String fundCode,
                @ToolParam(name = "poolType", description = "池类别", required = false) String poolType,
                @ToolParam(name = "targetWeight", description = "目标配置权重(0~1)", required = false) Double targetWeight,
                @ToolParam(name = "reason", description = "更新理由", required = false) String reason) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("fundCode", fundCode);
                    if (poolType != null) args.put("poolType", poolType);
                    if (targetWeight != null) args.put("targetWeight", targetWeight);
                    if (reason != null) args.put("reason", reason);
                    return mcpClient.callToolSync("update_fund_pool", args, userId);
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] update_fund_pool fallback to local tool: fundCode={}, userId={}, error={}", fundCode, userId, e.getMessage());
                }
            }
            if (tool != null) {
                return tool.updateFund(userId, fundCode, poolType, targetWeight, reason);
            }
            return "{\"error\":\"FundPoolTool is not available locally\"}";
        }

        @Tool(name = "remove_from_fund_pool", description = "从当前用户的私有基金池中移出指定标的")
        public String removeFund(
                @ToolConstraint(regexPattern = "^\\d{6}$")
                @ToolParam(name = "fundCode", description = "六位基金代码") String fundCode,
                @ToolParam(name = "poolType", description = "池类别", required = false) String poolType) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("fundCode", fundCode);
                    if (poolType != null) args.put("poolType", poolType);
                    return mcpClient.callToolSync("remove_from_fund_pool", args, userId);
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] remove_from_fund_pool fallback to local tool: fundCode={}, userId={}, error={}", fundCode, userId, e.getMessage());
                }
            }
            if (tool != null) {
                return tool.removeFund(userId, fundCode, poolType);
            }
            return "{\"error\":\"FundPoolTool is not available locally\"}";
        }

        @HighRiskTool(category = "ASSET_TRANSACTION", riskLevel = "HIGH", requiresApproval = true)
        @Tool(name = "clear_fund_pool", description = "清空指定分类或全部用户基金池")
        public String clearPool(
                @ToolParam(name = "poolType", description = "池类别(如 CORE/TACTICAL)，为空则清空默认池", required = false) String poolType) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    Map<String, Object> args = new LinkedHashMap<>();
                    if (poolType != null) args.put("poolType", poolType);
                    return mcpClient.callToolSync("clear_fund_pool", args, userId);
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] clear_fund_pool fallback to local tool: userId={}, error={}", userId, e.getMessage());
                }
            }
            if (tool != null) {
                return tool.clearPool(userId, poolType);
            }
            return "{\"error\":\"FundPoolTool is not available locally\"}";
        }

        @Tool(name = "query_fund_pool", description = "查询当前用户的私有基金池列表", readOnly = true)
        public String queryPool(
                @ToolParam(name = "poolType", description = "池类别筛选，为空则查询全部", required = false) String poolType) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    Map<String, Object> args = new LinkedHashMap<>();
                    if (poolType != null) args.put("poolType", poolType);
                    return mcpClient.callToolSync("query_fund_pool", args, userId);
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] query_fund_pool fallback to local tool: userId={}, error={}", userId, e.getMessage());
                }
            }
            if (tool != null) {
                return tool.queryPool(userId, poolType);
            }
            return "{\"error\":\"FundPoolTool is not available locally\"}";
        }
    }

    public static final class StockWatchlistToolDelegate {
        private final StockWatchlistTool tool;
        private final ReactiveMcpClient mcpClient;
        private final Long userId;

        public StockWatchlistToolDelegate(@Nullable StockWatchlistTool tool, @Nullable ReactiveMcpClient mcpClient, @Nullable Long userId) {
            this.tool = tool;
            this.mcpClient = mcpClient;
            this.userId = userId;
        }

        public StockWatchlistToolDelegate(StockWatchlistTool tool, @Nullable Long userId) {
            this(tool, null, userId);
        }

        @Tool(name = "add_to_stock_watchlist", description = "将股票添加至当前用户的自选股列表")
        public String addStock(
                @ToolConstraint(regexPattern = "^\\d{6}$")
                @ToolParam(name = "stockCode", description = "六位股票代码") String stockCode,
                @ToolParam(name = "stockName", description = "股票名称", required = false) String stockName,
                @ToolParam(name = "groupName", description = "自选分组(如 核心/观察/高股息)", required = false) String groupName,
                @ToolParam(name = "targetPrice", description = "目标价格", required = false) Double targetPrice,
                @ToolParam(name = "notes", description = "关注说明或入选理由", required = false) String notes,
                @ToolParam(name = "priority", description = "优先级(HIGH/MEDIUM/LOW)", required = false) String priority) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("stockCode", stockCode);
                    if (stockName != null) args.put("stockName", stockName);
                    if (groupName != null) args.put("groupName", groupName);
                    if (targetPrice != null) args.put("targetPrice", targetPrice);
                    if (notes != null) args.put("notes", notes);
                    if (priority != null) args.put("priority", priority);
                    return mcpClient.callToolSync("add_to_stock_watchlist", args, userId);
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] add_to_stock_watchlist fallback to local tool: stockCode={}, userId={}, error={}", stockCode, userId, e.getMessage());
                }
            }
            if (tool != null) {
                return tool.addStock(userId, stockCode, stockName, groupName, targetPrice, notes, priority);
            }
            return "{\"error\":\"StockWatchlistTool is not available locally\"}";
        }

        @Tool(name = "update_stock_watchlist", description = "更新自选股的目标价或关注说明")
        public String updateStock(
                @ToolConstraint(regexPattern = "^\\d{6}$")
                @ToolParam(name = "stockCode", description = "六位股票代码") String stockCode,
                @ToolParam(name = "groupName", description = "分组名称", required = false) String groupName,
                @ToolParam(name = "targetPrice", description = "目标价格", required = false) Double targetPrice,
                @ToolParam(name = "notes", description = "关注说明", required = false) String notes) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("stockCode", stockCode);
                    if (groupName != null) args.put("groupName", groupName);
                    if (targetPrice != null) args.put("targetPrice", targetPrice);
                    if (notes != null) args.put("notes", notes);
                    return mcpClient.callToolSync("update_stock_watchlist", args, userId);
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] update_stock_watchlist fallback to local tool: stockCode={}, userId={}, error={}", stockCode, userId, e.getMessage());
                }
            }
            if (tool != null) {
                return tool.updateStock(userId, stockCode, groupName, targetPrice, notes);
            }
            return "{\"error\":\"StockWatchlistTool is not available locally\"}";
        }

        @Tool(name = "remove_from_stock_watchlist", description = "从自选股中移除指定股票")
        public String removeStock(
                @ToolConstraint(regexPattern = "^\\d{6}$")
                @ToolParam(name = "stockCode", description = "六位股票代码") String stockCode,
                @ToolParam(name = "groupName", description = "分组名称", required = false) String groupName) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("stockCode", stockCode);
                    if (groupName != null) args.put("groupName", groupName);
                    return mcpClient.callToolSync("remove_from_stock_watchlist", args, userId);
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] remove_from_stock_watchlist fallback to local tool: stockCode={}, userId={}, error={}", stockCode, userId, e.getMessage());
                }
            }
            if (tool != null) {
                return tool.removeStock(userId, stockCode, groupName);
            }
            return "{\"error\":\"StockWatchlistTool is not available locally\"}";
        }

        @HighRiskTool(category = "ASSET_TRANSACTION", riskLevel = "HIGH", requiresApproval = true)
        @Tool(name = "clear_stock_watchlist", description = "清空自选股分组")
        public String clearWatchlist(
                @ToolParam(name = "groupName", description = "分组名称，为空则清空默认分组", required = false) String groupName) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    Map<String, Object> args = new LinkedHashMap<>();
                    if (groupName != null) args.put("groupName", groupName);
                    return mcpClient.callToolSync("clear_stock_watchlist", args, userId);
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] clear_stock_watchlist fallback to local tool: userId={}, error={}", userId, e.getMessage());
                }
            }
            if (tool != null) {
                return tool.clearWatchlist(userId, groupName);
            }
            return "{\"error\":\"StockWatchlistTool is not available locally\"}";
        }

        @Tool(name = "query_stock_watchlist", description = "查询当前用户的自选股列表", readOnly = true)
        public String queryWatchlist(
                @ToolParam(name = "groupName", description = "分组名称筛选，为空则查询全部", required = false) String groupName) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    Map<String, Object> args = new LinkedHashMap<>();
                    if (groupName != null) args.put("groupName", groupName);
                    return mcpClient.callToolSync("query_stock_watchlist", args, userId);
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] query_stock_watchlist fallback to local tool: userId={}, error={}", userId, e.getMessage());
                }
            }
            if (tool != null) {
                return tool.queryWatchlist(userId, groupName);
            }
            return "{\"error\":\"StockWatchlistTool is not available locally\"}";
        }
    }

    public static final class FundProfileToolDelegate {
        private final FundProfileTool tool;
        private final ReactiveMcpClient mcpClient;

        public FundProfileToolDelegate(@Nullable FundProfileTool tool, @Nullable ReactiveMcpClient mcpClient) {
            this.tool = tool;
            this.mcpClient = mcpClient;
        }

        @Tool(name = "query_fund_profile", description = "按6位基金代码或名称关键词模糊查询公募基金全维档案与实时净值指标", readOnly = true)
        public String queryFundProfile(
                @ToolParam(name = "keyword", description = "6位基金代码或中文名称/简称关键词") String keyword) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    return mcpClient.callToolSync("query_fund_profile", Map.of("keyword", keyword));
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] query_fund_profile fallback to local tool: keyword={}, error={}", keyword, e.getMessage());
                }
            }
            if (tool != null) {
                return tool.getFundProfile(keyword);
            }
            return "{\"error\":\"FundProfileTool is not available locally\"}";
        }
    }

    public static final class StockProfileToolDelegate {
        private final StockProfileTool tool;
        private final ReactiveMcpClient mcpClient;

        public StockProfileToolDelegate(@Nullable StockProfileTool tool, @Nullable ReactiveMcpClient mcpClient) {
            this.tool = tool;
            this.mcpClient = mcpClient;
        }

        @Tool(name = "query_stock_profile", description = "按6位股票代码或股票简称模糊查询股票全维基本面与估值指标", readOnly = true)
        public String queryStockProfile(
                @ToolParam(name = "keyword", description = "6位股票代码或股票简称/拼音关键词") String keyword) {
            if (mcpClient != null && mcpClient.isEnabled()) {
                try {
                    return mcpClient.callToolSync("query_stock_profile", Map.of("keyword", keyword));
                } catch (Exception e) {
                    log.warn("[MCP TOOL FALLBACK] query_stock_profile fallback to local tool: keyword={}, error={}", keyword, e.getMessage());
                }
            }
            if (tool != null) {
                return tool.getStockProfile(keyword);
            }
            return "{\"error\":\"StockProfileTool is not available locally\"}";
        }
    }
}

