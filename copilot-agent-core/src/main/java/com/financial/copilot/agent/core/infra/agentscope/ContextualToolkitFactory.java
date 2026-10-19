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
import com.financial.copilot.agent.tools.stock.StockQuantAnalysisTool;
import com.financial.copilot.agent.tools.stock.StockScreeningTool;
import com.financial.copilot.common.fund.dto.FundScreeningCriteria;
import com.financial.copilot.common.stock.dto.StockScreeningCriteria;
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
                                    @Autowired(required = false) @Nullable ReactiveMcpClient mcpClient) {
        this.roleProperties = Objects.requireNonNull(roleProperties);
        this.permissionService = Objects.requireNonNull(permissionService);

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
        Objects.requireNonNull(role, "role must not be null");
        Long userId = extractUserId(context);

        List<String> baseTools = roleProperties.getToolsFor(role);
        Set<String> allowedTools = permissionService.getAllowedTools(userId);

        // 若用户拥有全量权限，直接复用缓存的全量 Toolkit
        if (allowedTools != null && allowedTools.contains("*")) {
            return cachedFullToolkits.computeIfAbsent(role, this::buildFullToolkit);
        }

        // 否则严格计算交集：Role 基准工具 ∩ 用户授权白名单
        Toolkit dynamicToolkit = new Toolkit();
        for (String toolName : baseTools) {
            if (permissionService.isToolAllowed(userId, toolName)) {
                Object provider = toolProviders.get(toolName);
                if (provider != null) {
                    dynamicToolkit.registerTool(provider);
                }
            }
        }
        return dynamicToolkit;
    }

    private Toolkit buildFullToolkit(AgentRole role) {
        List<String> baseTools = roleProperties.getToolsFor(role);
        Toolkit toolkit = new Toolkit();
        for (String toolName : baseTools) {
            Object provider = toolProviders.get(toolName);
            if (provider != null) {
                toolkit.registerTool(provider);
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
}
