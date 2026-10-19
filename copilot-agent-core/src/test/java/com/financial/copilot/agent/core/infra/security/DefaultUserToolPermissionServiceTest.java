package com.financial.copilot.agent.core.infra.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultUserToolPermissionServiceTest {

    @Test
    @DisplayName("默认情况下放行所有工具 (*)")
    void defaultsToAllowAll() {
        DefaultUserToolPermissionService service = new DefaultUserToolPermissionService();
        assertThat(service.getAllowedTools(100L)).contains("*");
        assertThat(service.isToolAllowed(100L, "fund_metrics")).isTrue();
        assertThat(service.isToolAllowed(100L, "fund_report")).isTrue();
        assertThat(service.isToolAllowed(null, "fund_report")).isTrue();
    }

    @Test
    @DisplayName("配置特定用户白名单后严格按白名单鉴权")
    void enforcesExplicitWhitelist() {
        DefaultUserToolPermissionService service = new DefaultUserToolPermissionService();
        long normalUser = 101L;
        service.setUserWhitelist(normalUser, Set.of("fund_metrics", "fund_holdings"));

        assertThat(service.isToolAllowed(normalUser, "fund_metrics")).isTrue();
        assertThat(service.isToolAllowed(normalUser, "fund_holdings")).isTrue();
        assertThat(service.isToolAllowed(normalUser, "fund_report")).isFalse();
        assertThat(service.isToolAllowed(normalUser, "screen_funds")).isFalse();

        // 另一个用户不受影响
        assertThat(service.isToolAllowed(200L, "fund_report")).isTrue();

        // 清理白名单后恢复全量
        service.clearUserWhitelist(normalUser);
        assertThat(service.isToolAllowed(normalUser, "fund_report")).isTrue();
    }
}
