package com.financial.copilot;

import com.financial.copilot.agent.tools.graph.FinancialGraphTool;
import com.financial.copilot.data.graph.adapter.Neo4jFinancialGraphAdapter;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.neo4j.core.Neo4jClient;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证应用关闭图谱后无需 Neo4j，开启时恢复驱动和查询能力。 */
class Neo4jConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FinancialCopilotApplication.class)
            .withPropertyValues("spring.sql.init.mode=never",
                    "spring.datasource.url=jdbc:postgresql://localhost:1/unavailable",
                    "spring.neo4j.uri=bolt://localhost:1",
                    "copilot.llm.api-key=sk-placeholder");

    @Test
    void disabledStartsWithoutGraphInfrastructure() {
        runner.withPropertyValues("copilot.neo4j.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(Driver.class);
            assertThat(context).doesNotHaveBean(Neo4jClient.class);
            assertThat(context).doesNotHaveBean(Neo4jFinancialGraphAdapter.class);
            assertThat(context).doesNotHaveBean(FinancialGraphTool.class);
        });
    }

    @Test
    void enabledRegistersGraphInfrastructure() {
        runner.withPropertyValues("copilot.neo4j.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(Driver.class);
            assertThat(context).hasSingleBean(Neo4jClient.class);
            assertThat(context).hasSingleBean(FinancialGraphTool.class);
        });
    }
}
