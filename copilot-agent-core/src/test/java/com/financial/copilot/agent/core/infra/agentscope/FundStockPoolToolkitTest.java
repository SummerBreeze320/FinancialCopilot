package com.financial.copilot.agent.core.infra.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.business.agents.AgentRole;
import com.financial.copilot.agent.core.infra.config.AgentRoleProperties;
import com.financial.copilot.agent.core.infra.context.ConversationContextSnapshot;
import com.financial.copilot.agent.core.infra.context.ExecutionIdentity;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.infra.dag.runtime.GraphRunRequest;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;
import com.financial.copilot.agent.core.infra.security.DefaultUserToolPermissionService;
import com.financial.copilot.agent.tools.fund.FundHoldingsQueryTool;
import com.financial.copilot.agent.tools.fund.FundPoolTool;
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import com.financial.copilot.agent.tools.fund.FundScreeningTool;
import com.financial.copilot.agent.tools.stock.StockQuantAnalysisTool;
import com.financial.copilot.agent.tools.stock.StockScreeningTool;
import com.financial.copilot.agent.tools.stock.StockWatchlistTool;
import com.financial.copilot.common.intent.IntentType;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.financial.copilot.agent.tools.fund.FundProfileTool;
import com.financial.copilot.agent.tools.stock.StockProfileTool;
import com.financial.copilot.agent.core.infra.mcp.ReactiveMcpClient;

@DisplayName("FundStockPoolToolkit 基金池与自选股 Toolkit 自动装配测试")
class FundStockPoolToolkitTest {

    private ContextualToolkitFactory factory;
    private FundPoolTool fundPoolTool;
    private StockWatchlistTool stockWatchlistTool;
    private FundProfileTool fundProfileTool;
    private StockProfileTool stockProfileTool;

    @BeforeEach
    void setUp() {
        fundPoolTool = mock(FundPoolTool.class);
        stockWatchlistTool = mock(StockWatchlistTool.class);
        fundProfileTool = mock(FundProfileTool.class);
        stockProfileTool = mock(StockProfileTool.class);
        AgentRoleProperties roleProperties = new AgentRoleProperties();
        DefaultUserToolPermissionService permissionService = new DefaultUserToolPermissionService();

        factory = new ContextualToolkitFactory(
                roleProperties,
                permissionService,
                mock(FundQuantAnalysisTool.class),
                mock(FundHoldingsQueryTool.class),
                mock(FundReportRetrieverTool.class),
                mock(FundScreeningTool.class),
                null,
                null,
                mock(StockQuantAnalysisTool.class),
                mock(StockScreeningTool.class),
                new ObjectMapper(),
                null,
                fundPoolTool,
                stockWatchlistTool,
                fundProfileTool,
                stockProfileTool
        );
    }

    private NodeExecutionContext createContext(Long userId) {
        ExecutionIdentity identity = new ExecutionIdentity("tenant-1", userId, UUID.randomUUID(), "run-1", "session-1");
        ConversationContextSnapshot snapshot = new ConversationContextSnapshot(List.of(), null, "test prompt", null);
        GraphRunRequest request = new GraphRunRequest(identity, snapshot, null, false, null, null);
        return new NodeExecutionContext(request, "node-1", mock(ArtifactStore.class), new CancellationToken("node-1"));
    }

    @Test
    @DisplayName("FUND_ANALYZER 角色成功挂载基金池增删改查工具与标的档案查询工具")
    void testFundAnalyzerMountsFundPoolTools() {
        NodeExecutionContext context = createContext(100L);
        Toolkit toolkit = factory.createToolkit(AgentRole.FUND_ANALYZER, context);

        assertThat(toolkit.getToolNames()).contains(
                "add_to_fund_pool",
                "remove_from_fund_pool",
                "clear_fund_pool",
                "query_fund_pool",
                "query_fund_profile"
        );
    }

    @Test
    @DisplayName("STOCK_ANALYZER 角色成功挂载自选股增删改查工具与标的档案查询工具")
    void testStockAnalyzerMountsStockWatchlistTools() {
        NodeExecutionContext context = createContext(100L);
        Toolkit toolkit = factory.createToolkit(AgentRole.STOCK_ANALYZER, context);

        assertThat(toolkit.getToolNames()).contains(
                "add_to_stock_watchlist",
                "remove_from_stock_watchlist",
                "clear_stock_watchlist",
                "query_stock_watchlist",
                "query_stock_profile"
        );
    }

    @Test
    @DisplayName("意图驱动剪枝时正确保留基金池工具与档案查询工具")
    void testIntentPruningRetainsFundPoolAndProfile() {
        NodeExecutionContext context = createContext(100L);
        Toolkit toolkit = factory.createToolkit(AgentRole.FUND_ANALYZER, context, IntentType.FUND_DIAGNOSIS);

        assertThat(toolkit.getToolNames()).contains("add_to_fund_pool", "query_fund_pool", "query_fund_profile");
    }

    @Test
    @DisplayName("MCP 服务异常时 FundPoolToolDelegate 平滑降级至本地 Bean")
    void testFundPoolDelegateMcpFallback() {
        ReactiveMcpClient mockMcp = mock(ReactiveMcpClient.class);
        org.mockito.Mockito.when(mockMcp.isEnabled()).thenReturn(true);
        org.mockito.Mockito.when(mockMcp.callToolSync(org.mockito.ArgumentMatchers.eq("add_to_fund_pool"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("Connection refused"));

        org.mockito.Mockito.when(fundPoolTool.addFund(100L, "000001", "华夏成长", "CORE", 0.1, "看好", "绩优"))
                .thenReturn("{\"success\":true,\"source\":\"local\"}");

        ContextualToolkitFactory.FundPoolToolDelegate delegate =
                new ContextualToolkitFactory.FundPoolToolDelegate(fundPoolTool, mockMcp, 100L);

        String result = delegate.addFund("000001", "华夏成长", "CORE", 0.1, "看好", "绩优");
        assertThat(result).contains("\"source\":\"local\"");
        org.mockito.Mockito.verify(fundPoolTool).addFund(100L, "000001", "华夏成长", "CORE", 0.1, "看好", "绩优");
    }

    @Test
    @DisplayName("MCP 服务异常时 StockWatchlistToolDelegate 平滑降级至本地 Bean")
    void testStockWatchlistDelegateMcpFallback() {
        ReactiveMcpClient mockMcp = mock(ReactiveMcpClient.class);
        org.mockito.Mockito.when(mockMcp.isEnabled()).thenReturn(true);
        org.mockito.Mockito.when(mockMcp.callToolSync(org.mockito.ArgumentMatchers.eq("add_to_stock_watchlist"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("Connection refused"));

        org.mockito.Mockito.when(stockWatchlistTool.addStock(100L, "600519", "贵州茅台", "核心持仓", 2000.0, "白酒龙头", "HIGH"))
                .thenReturn("{\"success\":true,\"source\":\"local\"}");

        ContextualToolkitFactory.StockWatchlistToolDelegate delegate =
                new ContextualToolkitFactory.StockWatchlistToolDelegate(stockWatchlistTool, mockMcp, 100L);

        String result = delegate.addStock("600519", "贵州茅台", "核心持仓", 2000.0, "白酒龙头", "HIGH");
        assertThat(result).contains("\"source\":\"local\"");
        org.mockito.Mockito.verify(stockWatchlistTool).addStock(100L, "600519", "贵州茅台", "核心持仓", 2000.0, "白酒龙头", "HIGH");
    }

    @Test
    @DisplayName("MCP 服务异常时 ProfileToolDelegates 平滑降级至本地 Bean")
    void testProfileDelegatesMcpFallback() {
        ReactiveMcpClient mockMcp = mock(ReactiveMcpClient.class);
        org.mockito.Mockito.when(mockMcp.isEnabled()).thenReturn(true);
        org.mockito.Mockito.when(mockMcp.callToolSync(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("Connection refused"));

        org.mockito.Mockito.when(fundProfileTool.getFundProfile("000001")).thenReturn("{\"fundCode\":\"000001\"}");
        org.mockito.Mockito.when(stockProfileTool.getStockProfile("600519")).thenReturn("{\"stockCode\":\"600519\"}");

        ContextualToolkitFactory.FundProfileToolDelegate fundDelegate =
                new ContextualToolkitFactory.FundProfileToolDelegate(fundProfileTool, mockMcp);
        ContextualToolkitFactory.StockProfileToolDelegate stockDelegate =
                new ContextualToolkitFactory.StockProfileToolDelegate(stockProfileTool, mockMcp);

        assertThat(fundDelegate.queryFundProfile("000001")).isEqualTo("{\"fundCode\":\"000001\"}");
        assertThat(stockDelegate.queryStockProfile("600519")).isEqualTo("{\"stockCode\":\"600519\"}");
    }
}
