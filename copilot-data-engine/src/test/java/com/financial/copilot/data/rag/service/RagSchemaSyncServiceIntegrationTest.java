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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RagSchemaSyncServiceIntegrationTest {

    @Test
    @DisplayName("端到端验证指标与板块内存流批量向量化及双库同步入库与混合检索")
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

        // 3. 内存模拟流，纯净执行双库端到端向量化与写入
        String mockMetricsJson = """
            [
              {
                "mnemonic": "test_sync_metric",
                "index_name": "测试同步指标",
                "parent_name": "测试指标",
                "description": "用于双库流水线同步测试",
                "supported_usage": ["filter", "sort"],
                "aliases": ["测试同步"]
              }
            ]
            """;

        String mockSectorsJson = """
            [
              {
                "source_sector_id": "test_sync_sec_root",
                "parent_id": null,
                "name": "测试同步根板块",
                "is_leaf": false
              },
              {
                "source_sector_id": "test_sync_sec_leaf",
                "parent_id": "test_sync_sec_root",
                "name": "测试同步叶子板块",
                "is_leaf": true
              }
            ]
            """;

        try (InputStream mIs = new ByteArrayInputStream(mockMetricsJson.getBytes(StandardCharsets.UTF_8));
             InputStream sIs = new ByteArrayInputStream(mockSectorsJson.getBytes(StandardCharsets.UTF_8))) {

            RagSchemaSyncService.SyncReport report = syncService.syncAll(mIs, sIs);

            assertNotNull(report);
            assertEquals(1, report.metricsCount());
            assertEquals(2, report.sectorsCount());
            assertTrue(report.neo4jSynced());
        }

        // 4. 验证 PostgreSQL 数据持久化
        assertTrue(schemaAdapter.countMetrics() >= 1);
        assertTrue(schemaAdapter.countSectors() >= 2);

        // 5. 验证 Neo4j 节点与叶子节点展开
        List<String> leaves = graphAdapter.expandLeafSectors("test_sync_sec_root");
        assertNotNull(leaves);
        assertTrue(leaves.contains("test_sync_sec_leaf"));

        // 6. 验证基于向量余弦的三路混合召回
        float[] queryVec = embeddingAdapter.embed("测试同步指标");
        List<SchemaRecallResult.MetricMatch> metricMatches = schemaAdapter.searchMetrics("测试同步指标", queryVec, 3);
        assertFalse(metricMatches.isEmpty());
        assertEquals("test_sync_metric", metricMatches.get(0).metric().getMnemonic());
    }
}
