package com.financial.copilot.data.rag.adapter;

import com.financial.copilot.domain.rag.entity.RagFundMetric;
import com.financial.copilot.domain.rag.entity.RagFundSector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PostgresRagSchemaAdapterTest {

    private PostgresRagSchemaAdapter createLiveAdapter() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://127.0.0.1:5432/financial_copilot");
        dataSource.setUsername("postgres");
        dataSource.setPassword("123456");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return new PostgresRagSchemaAdapter(jdbcTemplate);
    }

    @Test
    @DisplayName("验证 Postgres 向量格式转换与单条 upsert 及查询")
    void testUpsertAndFindMetric() {
        PostgresRagSchemaAdapter adapter = createLiveAdapter();

        float[] testVec = new float[1024];
        testVec[0] = 0.88f;

        RagFundMetric testMetric = RagFundMetric.builder()
                .mnemonic("test_metric_1")
                .indexName("测试指标一")
                .parentName("测试大类")
                .description("仅供单元测试使用的指标")
                .embeddingText("测试指标一 test_metric_1")
                .embedding(testVec)
                .supportedUsage(List.of("filter", "sort"))
                .aliases(List.of("测试1", "指标1"))
                .enabled(true)
                .build();

        adapter.upsertMetrics(List.of(testMetric));

        Optional<RagFundMetric> found = adapter.findMetricByMnemonic("test_metric_1");
        assertTrue(found.isPresent(), "必须能查出刚写入的 test_metric_1");
        assertEquals("测试指标一", found.get().getIndexName());
        assertEquals("测试大类", found.get().getParentName());
        assertTrue(found.get().getAliases().contains("测试1"));
    }

    @Test
    @DisplayName("验证 Postgres 板块单条 upsert 及查询")
    void testUpsertAndFindSector() {
        PostgresRagSchemaAdapter adapter = createLiveAdapter();

        float[] testVec = new float[1024];
        testVec[0] = 0.66f;

        RagFundSector testSector = RagFundSector.builder()
                .sectorId("test_sector_1")
                .parentId(null)
                .name("测试板块一")
                .nameEn("Test Sector 1")
                .aliases(List.of("测试板块", "板块1"))
                .description("仅供单元测试使用的板块")
                .embeddingText("测试板块一 test_sector_1")
                .embedding(testVec)
                .isLeaf(true)
                .treeLevel(0)
                .fullPathNames("测试板块一")
                .enabled(true)
                .build();

        adapter.upsertSectors(List.of(testSector));

        Optional<RagFundSector> found = adapter.findSectorById("test_sector_1");
        assertTrue(found.isPresent(), "必须能查出刚写入的 test_sector_1");
        assertEquals("测试板块一", found.get().getName());
        assertEquals("测试板块一", found.get().getFullPathNames());
    }
}
