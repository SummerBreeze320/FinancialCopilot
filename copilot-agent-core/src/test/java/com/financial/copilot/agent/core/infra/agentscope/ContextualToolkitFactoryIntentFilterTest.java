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
import com.financial.copilot.agent.tools.fund.FundQuantAnalysisTool;
import com.financial.copilot.agent.tools.fund.FundReportRetrieverTool;
import com.financial.copilot.agent.tools.fund.FundScreeningTool;
import com.financial.copilot.agent.tools.stock.StockQuantAnalysisTool;
import com.financial.copilot.agent.tools.stock.StockScreeningTool;
import com.financial.copilot.common.intent.IntentType;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("ContextualToolkitFactory 意图驱动 RAG 工具剪枝过滤测试")
class ContextualToolkitFactoryIntentFilterTest {

    private ContextualToolkitFactory factory;

    @BeforeEach
    void setUp() {
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
                null
        );
    }

    private NodeExecutionContext createContext() {
        ExecutionIdentity identity = new ExecutionIdentity("tenant-1", 100L, UUID.randomUUID(), "run-1", "session-1");
        ConversationContextSnapshot snapshot = new ConversationContextSnapshot(List.of(), null, "test prompt", null);
        GraphRunRequest request = new GraphRunRequest(identity, snapshot, null, false, null, null);
        return new NodeExecutionContext(request, "node-1", mock(ArtifactStore.class), new CancellationToken("node-1"));
    }

    @Test
    @DisplayName("验证根据意图动态剪枝基准工具集合")
    void testFilterToolsByIntent() {
        Set<String> allTools = Set.of("screen_funds", "fund_metrics", "fund_holdings", "stock_metrics");

        // 初筛意图仅保留初筛与指标工具
        Set<String> screening = factory.filterToolsByIntent(allTools, IntentType.FUND_SCREENING);
        assertThat(screening).containsExactlyInAnyOrder("screen_funds", "fund_metrics");

        // 域外意图完全清空工具
        Set<String> ood = factory.filterToolsByIntent(allTools, IntentType.OUT_OF_DOMAIN);
        assertThat(ood).isEmpty();
    }

    @Test
    @DisplayName("验证传入意图时构建精简版动态 Toolkit")
    void testCreateToolkitWithIntentPruning() {
        NodeExecutionContext context = createContext();
        // FUND_ANALYZER 默认包含: fund_metrics, fund_holdings, fund_report
        // 当意图为 FUND_SCREENING 时，裁剪为仅保留交集 fund_metrics
        Toolkit toolkit = factory.createToolkit(AgentRole.FUND_ANALYZER, context, IntentType.FUND_SCREENING);
        assertThat(toolkit.getToolNames()).containsExactly("fund_metrics");
    }
}
