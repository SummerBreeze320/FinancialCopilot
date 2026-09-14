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

import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RagSchemaSyncServiceIntegrationTest {

    private File resolveFile(String relativePath) {
        File file = new File(relativePath);
        if (file.exists()) return file;
        File parentRelative = new File(".." + File.separator + relativePath);
        if (parentRelative.exists()) return parentRelative;
        return file;
    }

    @Test
    @DisplayName("端到端验证 169 项指标与 1712 项板块批量向量化及双写入库")
    void testEndToEndSyncAndHybridSearch() throws Exception {
        File metricsFile = resolveFile("docs/temp/metrics.json");
        File sectorsFile = resolveFile("docs/temp/sectors.json");
        assertTrue(metricsFile.exists(), "metrics.json 必须存在");
        assertTrue(sectorsFile.exists(), "sectors.json 必须存在");

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

        // 3. 执行全量数据同步与向量化写入
        RagSchemaSyncService.SyncReport report = syncService.syncFromFiles(metricsFile, sectorsFile);
        assertNotNull(report);
        assertEquals(169, report.metricsCount(), "指标同步数应为 169");
        assertEquals(1712, report.sectorsCount(), "板块同步数应为 1712");
        assertTrue(report.neo4jSynced(), "Neo4j 图拓扑同步应标记为成功");

        // 4. 验证 PostgreSQL 数据库落库总数
        assertTrue(schemaAdapter.countMetrics() >= 169L);
        assertTrue(schemaAdapter.countSectors() >= 1712L);

        // 5. 验证 Neo4j 节点与拓扑关系
        assertTrue(graphAdapter.countSectorNodes() >= 1712L);
        assertTrue(graphAdapter.countMetricNodes() >= 169L);

        // 6. 验证图谱拓扑下钻：中国上市ETF (1000009160000000) 递归展开叶子节点
        List<String> etfLeaves = graphAdapter.expandLeafSectors("1000009160000000");
        assertNotNull(etfLeaves);
        assertFalse(etfLeaves.isEmpty(), "中国上市ETF 应能展开出下级叶子板块代码");

        // 7. 验证三路混合语义召回
        float[] queryVec = embeddingAdapter.embed("近1年回报");
        List<SchemaRecallResult.MetricMatch> metricMatches = schemaAdapter.searchMetrics("近1年回报", queryVec, 5);
        assertFalse(metricMatches.isEmpty());
        assertEquals("f_return_1y", metricMatches.get(0).metric().getMnemonic(), "查询'近1年回报'排首位必须是 f_return_1y");

        float[] sectorVec = embeddingAdapter.embed("中国上市ETF");
        List<SchemaRecallResult.SectorMatch> sectorMatches = schemaAdapter.searchSectors("中国上市ETF", sectorVec, 5);
        assertFalse(sectorMatches.isEmpty());
        assertEquals("1000009160000000", sectorMatches.get(0).sector().getSectorId(), "查询'中国上市ETF'首位板块代码必须正确");
    }
}
