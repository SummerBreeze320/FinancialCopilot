package com.financial.copilot.agent.core.infra.config;

import com.financial.copilot.agent.core.business.agents.AgentRole;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <h1>Agent 角色工具能力声明式配置属性</h1>
 * <p>
 * 从 application.yml (前缀 {@code copilot.agent-roles}) 映射各个 AgentRole 所需声明的基础 Tool 清单。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Component
@ConfigurationProperties(prefix = "copilot.agent-roles")
public class AgentRoleProperties {

    /**
     * 角色到工具名称列表的映射。
     * key 支持枚举全名 (如 FUND_ANALYZER)、短横线 (如 fund-analyzer) 或小写 (如 fund_analyzer)。
     */
    private Map<String, List<String>> roles = new LinkedHashMap<>();

    /**
     * 获取指定角色声明的工具列表。若未配置，则回退到系统内置默认基准工具清单。
     *
     * @param role 角色枚举
     * @return 工具名称列表 (不可为 null)
     */
    public List<String> getToolsFor(AgentRole role) {
        if (role == null) {
            return Collections.emptyList();
        }
        if (roles != null && !roles.isEmpty()) {
            String enumKey = role.name();
            if (roles.containsKey(enumKey)) {
                return roles.get(enumKey);
            }
            String kebabKey = role.name().toLowerCase().replace('_', '-');
            if (roles.containsKey(kebabKey)) {
                return roles.get(kebabKey);
            }
            String lowerKey = role.name().toLowerCase();
            if (roles.containsKey(lowerKey)) {
                return roles.get(lowerKey);
            }
        }
        return getDefaultTools(role);
    }

    /**
     * 系统默认内置兜底工具清单
     *
     * @param role 角色枚举
     * @return 默认工具列表
     */
    public static List<String> getDefaultTools(AgentRole role) {
        if (role == null) {
            return Collections.emptyList();
        }
        return switch (role) {
            case FUND_ANALYZER -> List.of("fund_metrics", "fund_holdings", "fund_report");
            case FUND_SCREENER -> List.of("screen_funds", "schema_rag");
            case FUND_COMPARATOR -> List.of("compare_metrics", "compare_holdings", "compare_reports", "shared_holdings");
            case STOCK_ANALYZER -> List.of("stock_metrics");
            case STOCK_SCREENER -> List.of("screen_stocks");
            case STOCK_COMPARATOR -> List.of("compare_stock_metrics");
            case REPORT_SYNTHESIZER, REVIEWER -> List.of("read_research_artifacts");
        };
    }
}
