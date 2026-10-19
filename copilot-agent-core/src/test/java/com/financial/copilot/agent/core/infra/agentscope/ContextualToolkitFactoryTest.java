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
import com.financial.copilot.agent.tools.rag.FinancialSchemaRagTool;
import com.financial.copilot.agent.tools.stock.StockQuantAnalysisTool;
import com.financial.copilot.agent.tools.stock.StockScreeningTool;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ContextualToolkitFactoryTest {

    private AgentRoleProperties roleProperties;
    private DefaultUserToolPermissionService permissionService;
    private ContextualToolkitFactory factory;

    @BeforeEach
    void setUp() {
        roleProperties = new AgentRoleProperties();
        permissionService = new DefaultUserToolPermissionService();
        factory = new ContextualToolkitFactory(
                roleProperties,
                permissionService,
                mock(FundQuantAnalysisTool.class),
                mock(FundHoldingsQueryTool.class),
                mock(FundReportRetrieverTool.class),
                mock(FundScreeningTool.class),
                mock(FinancialSchemaRagTool.class),
                null,
                mock(StockQuantAnalysisTool.class),
                mock(StockScreeningTool.class),
                new ObjectMapper(),
                null
        );
    }

    private NodeExecutionContext createContextForUser(Long userId) {
        ExecutionIdentity identity = new ExecutionIdentity("tenant-1", userId, UUID.randomUUID(), "run-1", "session-1");
        ConversationContextSnapshot snapshot = new ConversationContextSnapshot(List.of(), null, "test prompt", null);
        GraphRunRequest request = new GraphRunRequest(identity, snapshot, null, false, null, null);
        return new NodeExecutionContext(request, "node-1", mock(ArtifactStore.class), new CancellationToken("node-1"));
    }

    @Test
    @DisplayName("默认情况下用户获得该角色全部基准工具")
    void defaultUserReceivesAllRoleTools() {
        NodeExecutionContext context = createContextForUser(100L);
        Toolkit toolkit = factory.createToolkit(AgentRole.FUND_ANALYZER, context);
        assertThat(toolkit.getToolNames()).containsExactlyInAnyOrder("fund_metrics", "fund_holdings", "fund_report");
    }

    @Test
    @DisplayName("上下文为空时放行默认工具")
    void nullContextReceivesAllRoleTools() {
        Toolkit toolkit = factory.createToolkit(AgentRole.FUND_ANALYZER, null);
        assertThat(toolkit.getToolNames()).containsExactlyInAnyOrder("fund_metrics", "fund_holdings", "fund_report");
    }

    @Test
    @DisplayName("普通用户白名单限制：剔除无权的 fund_report 工具")
    void regularUserRestrictedWhitelistFiltersOutUnauthorizedTools() {
        long normalUserId = 101L;
        // 普通用户只授权基础查询，未授权深度季报解读 fund_report
        permissionService.setUserWhitelist(normalUserId, Set.of("fund_metrics", "fund_holdings"));

        NodeExecutionContext context = createContextForUser(normalUserId);
        Toolkit toolkit = factory.createToolkit(AgentRole.FUND_ANALYZER, context);

        assertThat(toolkit.getToolNames()).containsExactlyInAnyOrder("fund_metrics", "fund_holdings");
        assertThat(toolkit.getToolNames()).doesNotContain("fund_report");
    }

    @Test
    @DisplayName("VIP用户获得完整白名单，与普通用户严格隔离")
    void vipUserIsolation() {
        long normalUserId = 101L;
        long vipUserId = 888L;

        permissionService.setUserWhitelist(normalUserId, Set.of("fund_metrics"));
        // VIP 用户拥有全量
        permissionService.setUserWhitelist(vipUserId, Set.of("fund_metrics", "fund_holdings", "fund_report"));

        Toolkit normalToolkit = factory.createToolkit(AgentRole.FUND_ANALYZER, createContextForUser(normalUserId));
        Toolkit vipToolkit = factory.createToolkit(AgentRole.FUND_ANALYZER, createContextForUser(vipUserId));

        assertThat(normalToolkit.getToolNames()).containsExactly("fund_metrics");
        assertThat(vipToolkit.getToolNames()).containsExactlyInAnyOrder("fund_metrics", "fund_holdings", "fund_report");
    }

    @Test
    @DisplayName("筛选器角色也能根据白名单动态剔除 schema_rag")
    void screenerFiltersRagTool() {
        long userId = 102L;
        permissionService.setUserWhitelist(userId, Set.of("screen_funds"));

        Toolkit toolkit = factory.createToolkit(AgentRole.FUND_SCREENER, createContextForUser(userId));
        assertThat(toolkit.getToolNames()).containsExactly("screen_funds");
        assertThat(toolkit.getToolNames()).doesNotContain("schema_rag");
    }
}
