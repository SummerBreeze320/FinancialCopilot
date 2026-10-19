package com.financial.copilot.agent.core.infra.config;

import com.financial.copilot.agent.core.business.agents.AgentRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRolePropertiesTest {

    @Test
    @DisplayName("未配置时回退到默认基准工具集")
    void fallbackToDefaultWhenNotConfigured() {
        AgentRoleProperties properties = new AgentRoleProperties();
        assertThat(properties.getToolsFor(AgentRole.FUND_ANALYZER))
                .containsExactly("fund_metrics", "fund_holdings", "fund_report", "fund_pool", "query_fund_profile");
        assertThat(properties.getToolsFor(AgentRole.FUND_SCREENER))
                .containsExactly("screen_funds", "schema_rag", "fund_pool", "query_fund_profile");
        assertThat(properties.getToolsFor(AgentRole.FUND_COMPARATOR))
                .containsExactly("compare_metrics", "compare_holdings", "compare_reports", "shared_holdings");
    }

    @Test
    @DisplayName("支持通过多种 key 形式覆盖角色工具清单")
    void supportsCustomConfigurationKeys() {
        AgentRoleProperties properties = new AgentRoleProperties();
        properties.setRoles(Map.of(
                "fund-analyzer", List.of("fund_metrics", "fund_holdings"),
                "FUND_SCREENER", List.of("screen_funds")
        ));

        assertThat(properties.getToolsFor(AgentRole.FUND_ANALYZER))
                .containsExactly("fund_metrics", "fund_holdings");
        assertThat(properties.getToolsFor(AgentRole.FUND_SCREENER))
                .containsExactly("screen_funds");
        // 未覆盖的依然使用默认
        assertThat(properties.getToolsFor(AgentRole.STOCK_ANALYZER))
                .containsExactly("stock_metrics", "stock_watchlist", "query_stock_profile");
    }
}
