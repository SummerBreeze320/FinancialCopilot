package com.financial.copilot.mcp.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundPoolTool;
import com.financial.copilot.agent.tools.fund.FundProfileTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import com.financial.copilot.agent.tools.fund.FundScreeningTool;
import com.financial.copilot.agent.tools.stock.StockProfileTool;
import com.financial.copilot.agent.tools.stock.StockQuantAnalysisTool;
import com.financial.copilot.agent.tools.stock.StockScreeningTool;
import com.financial.copilot.agent.tools.stock.StockTechnicalAnalysisTool;
import com.financial.copilot.agent.tools.stock.StockWatchlistTool;
import com.financial.copilot.common.fund.dto.FundScreeningCriteria;
import com.financial.copilot.common.stock.dto.StockScreeningCriteria;
import com.financial.copilot.mcp.config.McpServerProperties;
import com.financial.copilot.mcp.context.McpUserContext;
import com.financial.copilot.mcp.protocol.McpToolCallResult;
import com.financial.copilot.mcp.protocol.McpToolDefinition;
import com.financial.copilot.mcp.registry.DynamicHttpToolHandler;
import com.financial.copilot.mcp.registry.DynamicToolConfig;
import com.financial.copilot.mcp.registry.McpToolHandler;
import com.financial.copilot.mcp.registry.McpToolRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import com.financial.copilot.mcp.config.McpVirtualThreadConfig;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * <h1>内置金融权威量化工具与动态配置绑定装配器</h1>
 * <p>
 * 职责：专职将公募基金、A股个股等客观、确定性的事实量化工具暴露给 MCP 生态，
 * 杜绝大语言模型心算金融指标；同时装配 application.yml 中声明的动态外部 HTTP 工具。
 * 内部 Agent 认知增强（如语义向量匹配、DAG 内存产物读取）保留在主 Agent 引擎中本地调用。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FinancialToolsBinder {

    private final McpToolRegistry registry;
    private final McpServerProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private FundQuantAnalysisTool fundQuant;

    @Autowired(required = false)
    private FundScreeningTool fundScreening;

    @Autowired(required = false)
    private FundHoldingsQueryTool fundHoldings;

    @Autowired(required = false)
    private FundReportRetrieverTool fundReports;

    @Autowired(required = false)
    private FundProfileTool fundProfile;

    @Autowired(required = false)
    private FundPoolTool fundPool;

    @Autowired(required = false)
    private StockQuantAnalysisTool stockQuant;

    @Autowired(required = false)
    private StockScreeningTool stockScreening;

    @Autowired(required = false)
    private StockTechnicalAnalysisTool stockTechnical;

    @Autowired(required = false)
    private StockProfileTool stockProfile;

    @Autowired(required = false)
    private StockWatchlistTool stockWatchlist;

    public FinancialToolsBinder(McpToolRegistry registry,
                                McpServerProperties properties,
                                @Nullable WebClient.Builder webClientBuilder,
                                @Nullable ObjectMapper objectMapper) {
        this.registry = registry;
        this.properties = properties;
        this.webClientBuilder = webClientBuilder != null ? webClientBuilder : WebClient.builder();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public void setFundQuant(FundQuantAnalysisTool fundQuant) {
        this.fundQuant = fundQuant;
    }

    public void setFundScreening(FundScreeningTool fundScreening) {
        this.fundScreening = fundScreening;
    }

    public void setFundHoldings(FundHoldingsQueryTool fundHoldings) {
        this.fundHoldings = fundHoldings;
    }

    public void setFundReports(FundReportRetrieverTool fundReports) {
        this.fundReports = fundReports;
    }

    public void setStockQuant(StockQuantAnalysisTool stockQuant) {
        this.stockQuant = stockQuant;
    }

    public void setStockScreening(StockScreeningTool stockScreening) {
        this.stockScreening = stockScreening;
    }

    public void setStockTechnical(StockTechnicalAnalysisTool stockTechnical) {
        this.stockTechnical = stockTechnical;
    }

    public void setFundProfile(FundProfileTool fundProfile) {
        this.fundProfile = fundProfile;
    }

    public void setFundPool(FundPoolTool fundPool) {
        this.fundPool = fundPool;
    }

    public void setStockProfile(StockProfileTool stockProfile) {
        this.stockProfile = stockProfile;
    }

    public void setStockWatchlist(StockWatchlistTool stockWatchlist) {
        this.stockWatchlist = stockWatchlist;
    }

    @PostConstruct
    public void bindAll() {
        bindNativeTools();
        bindDynamicTools();
        log.info("[MCP TOOLS-BINDER] 金融量化工具挂载完成，当前 MCP 服务有效工具数: {}", registry.count());
    }

    /**
     * 注册核心金融权威事实工具
     */
    public void bindNativeTools() {
        // 1. 基金量化指标
        if (fundQuant != null) {
            registerTool("fund_metrics",
                    "查询基金收益风险量化指标 (区间收益率、最大回撤、夏普比率、卡玛比率、波动率)",
                    Map.of(
                            "fundCode", prop("string", "6位公募基金代码 (例如 005827)", true),
                            "startDate", prop("string", "统计起始日期 (YYYY-MM-DD，可选)", false),
                            "endDate", prop("string", "统计截止日期 (YYYY-MM-DD，可选)", false)
                    ),
                    args -> {
                        String code = str(args, "fundCode");
                        String start = str(args, "startDate");
                        String end = str(args, "endDate");
                        return fundQuant.getFundMetrics(code, start, end);
                    });
        }

        // 2. 基金多维筛选
        if (fundScreening != null) {
            Map<String, Map<String, Object>> screenProps = new LinkedHashMap<>();
            screenProps.put("fundType", prop("string", "基金类型 (如 股票型, 混合型, 债券型)", false));
            screenProps.put("sectorTheme", prop("string", "行业主题", false));
            screenProps.put("minScaleInBillion", prop("number", "最小规模(亿元)", false));
            screenProps.put("maxScaleInBillion", prop("number", "最大规模(亿元)", false));
            screenProps.put("maxDrawdown3YLimit", prop("number", "三年最大回撤上限(%)", false));
            screenProps.put("minSharpe3Y", prop("number", "三年最小夏普比率", false));
            screenProps.put("minReturn3Y", prop("number", "三年最小回报(%)", false));
            screenProps.put("minManagerTenureYears", prop("integer", "基金经理最小任职年限", false));
            screenProps.put("sortBy", prop("string", "排序指标", false));
            screenProps.put("sortOrder", prop("string", "排序方向 (ASC或DESC)", false));
            screenProps.put("limit", prop("integer", "返回标的数量上限 (默认 10)", false));

            registerTool("screen_funds",
                    "按结构化条件筛选真实公募基金候选池 (支持规模、收益、回撤、夏普与任期过滤)",
                    screenProps,
                    args -> fundScreening.screenFunds(new FundScreeningCriteria(
                            str(args, "fundType"),
                            str(args, "sectorTheme"),
                            dbl(args, "minScaleInBillion"),
                            dbl(args, "maxScaleInBillion"),
                            dbl(args, "maxDrawdown3YLimit"),
                            dbl(args, "minSharpe3Y"),
                            dbl(args, "minReturn3Y"),
                            integer(args, "minManagerTenureYears"),
                            str(args, "sortBy"),
                            str(args, "sortOrder"),
                            integer(args, "limit")
                    )));
        }

        // 3. 基金持仓与资产配置
        if (fundHoldings != null) {
            registerTool("fund_holdings",
                    "查询基金主要持仓明细与前十大重仓股分布",
                    Map.of(
                            "fundCode", prop("string", "6位公募基金代码", true),
                            "reportQuarter", prop("string", "报告季度 (如 2024Q2，默认最新)", false)
                    ),
                    args -> {
                        String code = str(args, "fundCode");
                        String quarter = str(args, "reportQuarter");
                        return fundHoldings.getTopHoldings(code, quarter);
                    });
        }

        // 4. 基金定期研报观点检索
        if (fundReports != null) {
            registerTool("fund_report",
                    "查询基金最新季报观点与运作分析",
                    Map.of("fundCode", prop("string", "6位公募基金代码", true)),
                    args -> fundReports.getLatestQuarterlyReportView(str(args, "fundCode")));
        }

        // 5. 基金全维属性与档案检索 (新增)
        if (fundProfile != null) {
            registerTool("query_fund_profile",
                    "查询基金全维档案与关键业绩 (支持6位代码或中文模糊名称)",
                    Map.of("query", prop("string", "基金代码或基金名称 (例如 005827 或 易方达蓝筹)", true)),
                    args -> fundProfile.getFundProfile(str(args, "query")));
        }

        // 6. 私有基金池增删改查工具 (新增用户感知)
        if (fundPool != null) {
            registerUserAwareTool("add_to_fund_pool",
                    "将指定基金添加至当前用户的私有基金池",
                    Map.of(
                            "fundCode", prop("string", "6位纯数字基金代码", true),
                            "fundName", prop("string", "基金名称", false),
                            "poolType", prop("string", "池类别 (如 CORE/TACTICAL/OBSERVATION)", false),
                            "targetWeight", prop("number", "目标配置权重(0~1)", false),
                            "reason", prop("string", "入池理由", false),
                            "tags", prop("string", "标的标签", false)
                    ),
                    (userId, args) -> fundPool.addFund(userId, str(args, "fundCode"), str(args, "fundName"),
                            str(args, "poolType"), dbl(args, "targetWeight"), str(args, "reason"), str(args, "tags")));

            registerUserAwareTool("update_fund_pool",
                    "更新用户基金池中某标的的目标仓位或入池理由",
                    Map.of(
                            "fundCode", prop("string", "6位纯数字基金代码", true),
                            "poolType", prop("string", "池类别", false),
                            "targetWeight", prop("number", "目标配置权重(0~1)", false),
                            "reason", prop("string", "更新理由", false)
                    ),
                    (userId, args) -> fundPool.updateFund(userId, str(args, "fundCode"), str(args, "poolType"),
                            dbl(args, "targetWeight"), str(args, "reason")));

            registerUserAwareTool("remove_from_fund_pool",
                    "从当前用户的私有基金池中移出指定标的",
                    Map.of(
                            "fundCode", prop("string", "6位纯数字基金代码", true),
                            "poolType", prop("string", "池类别", false)
                    ),
                    (userId, args) -> fundPool.removeFund(userId, str(args, "fundCode"), str(args, "poolType")));

            registerUserAwareTool("clear_fund_pool",
                    "清空指定分类或全部用户基金池 (高危操作)",
                    Map.of(
                            "poolType", prop("string", "池类别，为空则清空默认池", false)
                    ),
                    (userId, args) -> fundPool.clearPool(userId, str(args, "poolType")));

            registerUserAwareTool("query_fund_pool",
                    "查询当前用户的私有基金池列表",
                    Map.of(
                            "poolType", prop("string", "池类别筛选", false)
                    ),
                    (userId, args) -> fundPool.queryPool(userId, str(args, "poolType")));
        }

        // 7. 股票估值和财务指标
        if (stockQuant != null) {
            registerTool("stock_metrics",
                    "查询股票估值和财务量化指标 (PE、PB、ROE、股息率、涨跌幅)",
                    Map.of("stockCode", prop("string", "A股股票代码 (例如 600519)", true)),
                    args -> stockQuant.getStockMetrics(str(args, "stockCode")));
        }

        // 8. 股票多维初筛
        if (stockScreening != null) {
            Map<String, Map<String, Object>> stockProps = new LinkedHashMap<>();
            stockProps.put("exchange", prop("string", "交易所代码 (SH/SZ)", false));
            stockProps.put("sector", prop("string", "所属申万行业", false));
            stockProps.put("minMarketCapBillion", prop("number", "最小市值(亿元)", false));
            stockProps.put("maxPeTtm", prop("number", "最大 PE(TTM)", false));
            stockProps.put("maxPb", prop("number", "最大 PB", false));
            stockProps.put("minRoe", prop("number", "最小 ROE(%)", false));
            stockProps.put("minDividendYield", prop("number", "最小股息率(%)", false));
            stockProps.put("sortBy", prop("string", "排序字段", false));
            stockProps.put("sortOrder", prop("string", "排序方向 (ASC或DESC)", false));
            stockProps.put("limit", prop("integer", "返回数量上限 (默认 10)", false));

            registerTool("screen_stocks",
                    "按结构化条件筛选真实 A 股标的池 (行业、市值、PE、PB、ROE、股息率)",
                    stockProps,
                    args -> stockScreening.screenStocks(new StockScreeningCriteria(
                            str(args, "exchange"),
                            str(args, "sector"),
                            dbl(args, "minMarketCapBillion"),
                            dbl(args, "maxPeTtm"),
                            dbl(args, "maxPb"),
                            dbl(args, "minRoe"),
                            dbl(args, "minDividendYield"),
                            str(args, "sortBy"),
                            str(args, "sortOrder"),
                            integer(args, "limit")
                    )));
        }

        // 9. 股票技术分析
        if (stockTechnical != null) {
            registerTool("stock_technical",
                    "获取个股全维度技术指标 (均线MA + RSI + MACD + 波动率)",
                    Map.of("stockCode", prop("string", "A股股票代码 (例如 600519.SH)", true)),
                    args -> stockTechnical.getTechnicalIndicators(str(args, "stockCode")));
        }

        // 10. 股票全维属性与档案检索 (新增)
        if (stockProfile != null) {
            registerTool("query_stock_profile",
                    "查询股票全维档案与实时估值 (支持6位代码或中文模糊名称)",
                    Map.of("query", prop("string", "股票代码或公司名称 (例如 600519 或 贵州茅台)", true)),
                    args -> stockProfile.getStockProfile(str(args, "query")));
        }

        // 11. 股票自选股增删改查工具 (新增用户感知)
        if (stockWatchlist != null) {
            registerUserAwareTool("add_to_stock_watchlist",
                    "将股票添加至当前用户的自选股列表",
                    Map.of(
                            "stockCode", prop("string", "6位纯数字股票代码", true),
                            "stockName", prop("string", "股票名称", false),
                            "groupName", prop("string", "自选分组 (如 核心/观察/高股息)", false),
                            "targetPrice", prop("number", "目标价格", false),
                            "notes", prop("string", "关注说明或入选理由", false),
                            "priority", prop("string", "优先级 (HIGH/MEDIUM/LOW)", false)
                    ),
                    (userId, args) -> stockWatchlist.addStock(userId, str(args, "stockCode"), str(args, "stockName"),
                            str(args, "groupName"), dbl(args, "targetPrice"), str(args, "notes"), str(args, "priority")));

            registerUserAwareTool("update_stock_watchlist",
                    "更新自选股的目标价或关注说明",
                    Map.of(
                            "stockCode", prop("string", "6位纯数字股票代码", true),
                            "groupName", prop("string", "分组名称", false),
                            "targetPrice", prop("number", "目标价格", false),
                            "notes", prop("string", "关注说明", false)
                    ),
                    (userId, args) -> stockWatchlist.updateStock(userId, str(args, "stockCode"), str(args, "groupName"),
                            dbl(args, "targetPrice"), str(args, "notes")));

            registerUserAwareTool("remove_from_stock_watchlist",
                    "从自选股中移除指定股票",
                    Map.of(
                            "stockCode", prop("string", "6位纯数字股票代码", true),
                            "groupName", prop("string", "分组名称", false)
                    ),
                    (userId, args) -> stockWatchlist.removeStock(userId, str(args, "stockCode"), str(args, "groupName")));

            registerUserAwareTool("clear_stock_watchlist",
                    "清空自选股分组 (高危操作)",
                    Map.of(
                            "groupName", prop("string", "分组名称，为空则清空默认分组", false)
                    ),
                    (userId, args) -> stockWatchlist.clearWatchlist(userId, str(args, "groupName")));

            registerUserAwareTool("query_stock_watchlist",
                    "查询当前用户的自选股列表",
                    Map.of(
                            "groupName", prop("string", "分组名称筛选", false)
                    ),
                    (userId, args) -> stockWatchlist.queryWatchlist(userId, str(args, "groupName")));
        }
    }

    /**
     * 加载 application.yml 中的动态工具配置
     */
    public void bindDynamicTools() {
        if (properties != null && properties.getDynamicTools() != null) {
            for (DynamicToolConfig toolConfig : properties.getDynamicTools()) {
                if (toolConfig.getName() != null && !toolConfig.getName().isBlank()) {
                    DynamicHttpToolHandler handler = new DynamicHttpToolHandler(toolConfig, webClientBuilder);
                    registry.register(handler);
                    log.info("[MCP TOOLS-BINDER] 成功挂载 YAML 动态工具: name={}", toolConfig.getName());
                }
            }
        }
    }

    private void registerTool(String name, String description,
                              Map<String, Map<String, Object>> properties,
                              Function<Map<String, Object>, String> executor) {
        Map<String, Object> props = new HashMap<>();
        List<String> required = new ArrayList<>();

        properties.forEach((propName, meta) -> {
            Map<String, Object> fieldSchema = new HashMap<>();
            fieldSchema.put("type", meta.get("type"));
            fieldSchema.put("description", meta.get("description"));
            props.put(propName, fieldSchema);

            if (Boolean.TRUE.equals(meta.get("required"))) {
                required.add(propName);
            }
        });

        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", props,
                "required", required
        );

        McpToolDefinition definition = McpToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();

        registry.register(new McpToolHandler() {
            @Override
            public McpToolDefinition getDefinition() {
                return definition;
            }

            @Override
            public Mono<McpToolCallResult> execute(Map<String, Object> arguments) {
                return Mono.fromCallable(() -> {
                            String result = executor.apply(arguments != null ? arguments : Map.of());
                            return McpToolCallResult.success(result);
                        })
                        .subscribeOn(McpVirtualThreadConfig.scheduler())
                        .onErrorResume(ex -> {
                            log.error("[MCP CALL-ERROR] 工具 {} 执行异常: {}", name, ex.getMessage(), ex);
                            return Mono.just(McpToolCallResult.error("工具执行失败: " + ex.getMessage()));
                        });
            }
        });
    }

    private void registerUserAwareTool(String name, String description,
                                       Map<String, Map<String, Object>> properties,
                                       BiFunction<Long, Map<String, Object>, String> executor) {
        Map<String, Object> props = new HashMap<>();
        List<String> required = new ArrayList<>();

        properties.forEach((propName, meta) -> {
            Map<String, Object> fieldSchema = new HashMap<>();
            fieldSchema.put("type", meta.get("type"));
            fieldSchema.put("description", meta.get("description"));
            props.put(propName, fieldSchema);

            if (Boolean.TRUE.equals(meta.get("required"))) {
                required.add(propName);
            }
        });

        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", props,
                "required", required
        );

        McpToolDefinition definition = McpToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();

        registry.register(new McpToolHandler() {
            @Override
            public McpToolDefinition getDefinition() {
                return definition;
            }

            @Override
            public Mono<McpToolCallResult> execute(Map<String, Object> arguments) {
                return McpUserContext.getCurrentUserId()
                        .publishOn(McpVirtualThreadConfig.scheduler())
                        .map(userId -> {
                            String result = executor.apply(userId, arguments != null ? arguments : Map.of());
                            return McpToolCallResult.success(result);
                        })
                        .onErrorResume(ex -> {
                            log.error("[MCP CALL-ERROR] 用户感知工具 {} 执行异常: {}", name, ex.getMessage(), ex);
                            return Mono.just(McpToolCallResult.error("工具执行失败: " + ex.getMessage()));
                        });
            }
        });
    }

    private static Map<String, Object> prop(String type, String desc, boolean required) {
        return Map.of("type", type, "description", desc, "required", required);
    }

    private static String str(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString().trim() : null;
    }

    private static Double dbl(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) return null;
        if (val instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(val.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) return null;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
