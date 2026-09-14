package com.financial.copilot.data.rag.service;

import com.financial.copilot.data.rag.adapter.Neo4jRagGraphAdapter;
import com.financial.copilot.data.rag.adapter.PostgresRagSchemaAdapter;
import com.financial.copilot.data.rag.embedding.OllamaEmbeddingAdapter;
import com.financial.copilot.data.rag.parser.MetricSectorDataParser;
import com.financial.copilot.domain.rag.entity.SchemaRecallResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.data.neo4j.core.DatabaseSelectionProvider;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RagSchemaSyncServiceIntegrationTest {

    @Test
    @DisplayName("端到端验证指标与板块样例批量向量化及双库同步入库与混合检索")
    void testEndToEndSyncAndHybridSearch() throws Exception {
        // 1. 数据源初始化
        DriverManagerDataSource pgDs = new DriverManagerDataSource();
        pgDs.setDriverClassName("org.postgresql.Driver");
        pgDs.setUrl("jdbc:postgresql://127.0.0.1:5432/financial_copilot");
        pgDs.setUsername("postgres");
        pgDs.setPassword("123456");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(pgDs);

        Driver neo4jDriver = GraphDatabase.driver("bolt://127.0.0.1:7687", AuthTokens.basic("neo4j", "123456"));
        Neo4jClient neo4jClient = Neo4jClient.create(neo4jDriver, DatabaseSelectionProvider.getDefaultSelectionProvider());

        // 2. 组件装配
        MetricSectorDataParser parser = new MetricSectorDataParser();
        OllamaEmbeddingAdapter embeddingAdapter = new OllamaEmbeddingAdapter("http://127.0.0.1:11434", "qwen3-embedding:0.6b", 32);
        PostgresRagSchemaAdapter schemaAdapter = new PostgresRagSchemaAdapter(jdbcTemplate);
        Neo4jRagGraphAdapter graphAdapter = new Neo4jRagGraphAdapter(neo4jClient);

        RagSchemaSyncService syncService = new RagSchemaSyncService(
                parser, embeddingAdapter, schemaAdapter, Optional.of(graphAdapter)
        );

        // 3. 从测试类路径读取样例数据，执行端到端同步流水线
        try (InputStream mIs = getClass().getResourceAsStream("/data/sample-metrics.json");
             InputStream sIs = getClass().getResourceAsStream("/data/sample-sectors.json")) {

            assertNotNull(mIs, "测试资源 /data/sample-metrics.json 必须存在");
            assertNotNull(sIs, "测试资源 /data/sample-sectors.json 必须存在");

            RagSchemaSyncService.SyncReport report = syncService.syncAll(mIs, sIs);

            assertNotNull(report);
            assertEquals(4, report.metricsCount(), "指标同步数量应为 4");
            assertEquals(4, report.sectorsCount(), "板块同步数量应为 4");
            assertTrue(report.neo4jSynced(), "Neo4j 必须同步成功");
        }

        // 4. 验证 PostgreSQL 数据持久化
        assertTrue(schemaAdapter.countMetrics() >= 4, "Postgres 指标总数应不少于 4");
        assertTrue(schemaAdapter.countSectors() >= 4, "Postgres 板块总数应不少于 4");

        // 5. 验证 Neo4j 节点与关系
        assertTrue(graphAdapter.countSectorNodes() >= 4, "Neo4j 板块节点应不少于 4");
        assertTrue(graphAdapter.countMetricNodes() >= 4, "Neo4j 指标节点应不少于 4");

        // 6. 验证 Neo4j 叶子节点展开
        List<String> etfLeaves = graphAdapter.expandLeafSectors("1000009160000000");
        assertNotNull(etfLeaves);
        assertTrue(etfLeaves.contains("1000009161000000"), "展开应包含股票型ETF");
        assertTrue(etfLeaves.contains("1000009162000000"), "展开应包含债券型ETF");

        // 7. 验证基于向量余弦的三路混合召回
        float[] queryVec = embeddingAdapter.embed("近1年收益率最高的基金");
        List<SchemaRecallResult.MetricMatch> metricMatches = schemaAdapter.searchMetrics("近1年收益率", queryVec, 3);
        assertFalse(metricMatches.isEmpty(), "必须能召回相关指标");
        assertEquals("f_return_1y", metricMatches.get(0).metric().getMnemonic());

        List<SchemaRecallResult.SectorMatch> sectorMatches = schemaAdapter.searchSectors("中国上市ETF", queryVec, 3);
        assertFalse(sectorMatches.isEmpty(), "必须能召回相关板块");
    }
}
