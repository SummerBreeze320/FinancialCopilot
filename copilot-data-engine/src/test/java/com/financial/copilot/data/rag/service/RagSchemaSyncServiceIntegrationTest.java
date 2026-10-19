package com.financial.copilot.data.rag.service;

import com.financial.copilot.data.rag.adapter.Neo4jRagGraphAdapter;
import com.financial.copilot.data.rag.adapter.PostgresRagSchemaAdapter;
import com.financial.copilot.data.rag.embedding.OllamaEmbeddingAdapter;
import com.financial.copilot.data.rag.parser.MetricSectorDataParser;
import com.financial.copilot.domain.shared.rag.entity.SchemaRecallResult;
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
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "copilot.integration", matches = "true")
class RagSchemaSyncServiceIntegrationTest {

    private String getDbPort() {
        return System.getProperty("DB_PORT", System.getenv().getOrDefault("DB_PORT", "15432"));
    }

    private String getNeo4jUri() {
        return System.getProperty("NEO4J_URI", System.getenv().getOrDefault("NEO4J_URI", "bolt://127.0.0.1:7687"));
    }

    private String getOllamaUrl() {
        return System.getProperty("EMBEDDING_BASE_URL", System.getenv().getOrDefault("EMBEDDING_BASE_URL", "http://127.0.0.1:11434"));
    }

    @Test
    @DisplayName("端到端验证指标与板块内存流批量向量化及双库同步入库与混合检索")
    void testEndToEndSyncAndHybridSearch() throws Exception {
        // 1. 数据源初始化
        DriverManagerDataSource pgDs = new DriverManagerDataSource();
        pgDs.setDriverClassName("org.postgresql.Driver");
        pgDs.setUrl("jdbc:postgresql://127.0.0.1:" + getDbPort() + "/financial_copilot");
        pgDs.setUsername("postgres");
        pgDs.setPassword("123456");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(pgDs);

        Driver neo4jDriver = GraphDatabase.driver(getNeo4jUri(), AuthTokens.basic("neo4j", "123456"));
        Neo4jClient neo4jClient = Neo4jClient.create(neo4jDriver, DatabaseSelectionProvider.getDefaultSelectionProvider());

        // 2. 组件装配
        MetricSectorDataParser parser = new MetricSectorDataParser();
        OllamaEmbeddingAdapter embeddingAdapter = new OllamaEmbeddingAdapter(getOllamaUrl(), "qwen3-embedding:0.6b", 32);
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

    @Test
    @DisplayName("全量真实文件向量化并双库同步入库 (PostgreSQL 16 + Neo4j 5.x + Ollama)")
    void testFullFilesSyncLive() throws Exception {
        File metricsFile = new File("docs/temp/metrics.json");
        File sectorsFile = new File("docs/temp/sectors.json");

        if (!metricsFile.exists()) {
            metricsFile = new File("../docs/temp/metrics.json");
            sectorsFile = new File("../docs/temp/sectors.json");
        }

        assertTrue(metricsFile.exists(), "metrics.json 文件必须存在: " + metricsFile.getAbsolutePath());
        assertTrue(sectorsFile.exists(), "sectors.json 文件必须存在: " + sectorsFile.getAbsolutePath());

        DriverManagerDataSource pgDs = new DriverManagerDataSource();
        pgDs.setDriverClassName("org.postgresql.Driver");
        pgDs.setUrl("jdbc:postgresql://127.0.0.1:" + getDbPort() + "/financial_copilot");
        pgDs.setUsername("postgres");
        pgDs.setPassword("123456");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(pgDs);

        Driver neo4jDriver = GraphDatabase.driver(getNeo4jUri(), AuthTokens.basic("neo4j", "123456"));
        Neo4jClient neo4jClient = Neo4jClient.create(neo4jDriver, DatabaseSelectionProvider.getDefaultSelectionProvider());

        MetricSectorDataParser parser = new MetricSectorDataParser();
        OllamaEmbeddingAdapter embeddingAdapter = new OllamaEmbeddingAdapter(getOllamaUrl(), "qwen3-embedding:0.6b", 32);
        PostgresRagSchemaAdapter schemaAdapter = new PostgresRagSchemaAdapter(jdbcTemplate);
        Neo4jRagGraphAdapter graphAdapter = new Neo4jRagGraphAdapter(neo4jClient);

        RagSchemaSyncService syncService = new RagSchemaSyncService(
                parser, embeddingAdapter, schemaAdapter, Optional.of(graphAdapter)
        );

        // 执行全量同步
        RagSchemaSyncService.SyncReport report = syncService.syncFromFiles(metricsFile, sectorsFile);
        assertNotNull(report);
        assertTrue(report.metricsCount() >= 168, "指标总数应大于等于 168");
        assertEquals(1712, report.sectorsCount(), "板块总数必须为 1712");
        assertTrue(report.neo4jSynced(), "Neo4j 必须同步成功");

        // 验证 PostgreSQL 记录数
        long pgMetricsCount = schemaAdapter.countMetrics();
        long pgSectorsCount = schemaAdapter.countSectors();
        assertTrue(pgMetricsCount >= 168);
        assertTrue(pgSectorsCount >= 1712);

        // 验证 Neo4j 拓扑节点数
        long neo4jMetricsCount = graphAdapter.countMetricNodes();
        long neo4jSectorsCount = graphAdapter.countSectorNodes();
        assertTrue(neo4jMetricsCount >= 168, "Neo4j 指标节点数: " + neo4jMetricsCount);
        assertTrue(neo4jSectorsCount >= 1712, "Neo4j 板块节点数: " + neo4jSectorsCount);

        // 验证真实向量与全文混合检索
        float[] queryVec = embeddingAdapter.embed("近1年回报率高的股票型ETF");
        List<SchemaRecallResult.MetricMatch> metricMatches = schemaAdapter.searchMetrics("近1年回报", queryVec, 5);
        assertFalse(metricMatches.isEmpty(), "必须能够召回近1年回报相关指标");
        assertEquals("f_return_1y", metricMatches.get(0).metric().getMnemonic());

        List<SchemaRecallResult.SectorMatch> sectorMatches = schemaAdapter.searchSectors("股票型ETF", queryVec, 5);
        assertFalse(sectorMatches.isEmpty(), "必须能够召回股票型ETF板块");
        assertTrue(sectorMatches.stream().anyMatch(s -> s.sector().getName().contains("股票")));
    }
}
