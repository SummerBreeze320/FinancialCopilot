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

@DisplayName("FundStockPoolToolkit 基金池与自选股 Toolkit 自动装配测试")
class FundStockPoolToolkitTest {

    private ContextualToolkitFactory factory;
    private FundPoolTool fundPoolTool;
    private StockWatchlistTool stockWatchlistTool;

    @BeforeEach
    void setUp() {
        fundPoolTool = mock(FundPoolTool.class);
        stockWatchlistTool = mock(StockWatchlistTool.class);
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
                stockWatchlistTool
        );
    }

    private NodeExecutionContext createContext(Long userId) {
        ExecutionIdentity identity = new ExecutionIdentity("tenant-1", userId, UUID.randomUUID(), "run-1", "session-1");
        ConversationContextSnapshot snapshot = new ConversationContextSnapshot(List.of(), null, "test prompt", null);
        GraphRunRequest request = new GraphRunRequest(identity, snapshot, null, false, null, null);
        return new NodeExecutionContext(request, "node-1", mock(ArtifactStore.class), new CancellationToken("node-1"));
    }

    @Test
    @DisplayName("FUND_ANALYZER 角色成功挂载基金池增删改查工具")
    void testFundAnalyzerMountsFundPoolTools() {
        NodeExecutionContext context = createContext(100L);
        Toolkit toolkit = factory.createToolkit(AgentRole.FUND_ANALYZER, context);

        assertThat(toolkit.getToolNames()).contains(
                "add_to_fund_pool",
                "remove_from_fund_pool",
                "clear_fund_pool",
                "query_fund_pool"
        );
    }

    @Test
    @DisplayName("STOCK_ANALYZER 角色成功挂载自选股增删改查工具")
    void testStockAnalyzerMountsStockWatchlistTools() {
        NodeExecutionContext context = createContext(100L);
        Toolkit toolkit = factory.createToolkit(AgentRole.STOCK_ANALYZER, context);

        assertThat(toolkit.getToolNames()).contains(
                "add_to_stock_watchlist",
                "remove_from_stock_watchlist",
                "clear_stock_watchlist",
                "query_stock_watchlist"
        );
    }

    @Test
    @DisplayName("意图驱动剪枝时正确保留基金池工具")
    void testIntentPruningRetainsFundPool() {
        NodeExecutionContext context = createContext(100L);
        Toolkit toolkit = factory.createToolkit(AgentRole.FUND_ANALYZER, context, IntentType.FUND_DIAGNOSIS);

        assertThat(toolkit.getToolNames()).contains("add_to_fund_pool", "query_fund_pool");
    }
}
