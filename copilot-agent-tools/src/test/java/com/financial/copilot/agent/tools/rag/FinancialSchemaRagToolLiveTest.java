package com.financial.copilot.agent.tools.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.data.rag.adapter.Neo4jRagGraphAdapter;
import com.financial.copilot.data.rag.adapter.PostgresRagSchemaAdapter;
import com.financial.copilot.data.rag.embedding.OllamaEmbeddingAdapter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.data.neo4j.core.DatabaseSelectionProvider;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Optional;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "copilot.integration", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinancialSchemaRagToolLiveTest {

    private FinancialSchemaRagTool ragTool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    void setUp() {
        String dbPort = System.getProperty("DB_PORT", System.getenv().getOrDefault("DB_PORT", "15432"));
        String neo4jUri = System.getProperty("NEO4J_URI", System.getenv().getOrDefault("NEO4J_URI", "bolt://127.0.0.1:7687"));
        String ollamaUrl = System.getProperty("EMBEDDING_BASE_URL", System.getenv().getOrDefault("EMBEDDING_BASE_URL", "http://127.0.0.1:11434"));

        DriverManagerDataSource pgDs = new DriverManagerDataSource();
        pgDs.setDriverClassName("org.postgresql.Driver");
        pgDs.setUrl("jdbc:postgresql://127.0.0.1:" + dbPort + "/financial_copilot");
        pgDs.setUsername("postgres");
        pgDs.setPassword("123456");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(pgDs);

        Driver neo4jDriver = GraphDatabase.driver(neo4jUri, AuthTokens.basic("neo4j", "123456"));
        Neo4jClient neo4jClient = Neo4jClient.create(neo4jDriver, DatabaseSelectionProvider.getDefaultSelectionProvider());

        OllamaEmbeddingAdapter embeddingAdapter = new OllamaEmbeddingAdapter(ollamaUrl, "qwen3-embedding:0.6b", 32);
        PostgresRagSchemaAdapter schemaAdapter = new PostgresRagSchemaAdapter(jdbcTemplate);
        Neo4jRagGraphAdapter graphAdapter = new Neo4jRagGraphAdapter(neo4jClient);

        ragTool = new FinancialSchemaRagTool(
                embeddingAdapter, schemaAdapter, Optional.of(graphAdapter), objectMapper
        );
    }

    @Test
    @DisplayName("真实链路：自然语言联合召回指标与板块 (match_metrics_and_sectors)")
    void testLiveMatchMetricsAndSectors() throws Exception {
        String query = "近1年收益大于20%的消费行业ETF";
        String jsonResult = ragTool.matchMetricsAndSectors(query, 5);

        assertNotNull(jsonResult);
        JsonNode root = objectMapper.readTree(jsonResult);
        assertEquals(query, root.path("query").asText());

        JsonNode matchedMetrics = root.path("matchedMetrics");
        assertFalse(matchedMetrics.isEmpty(), "必须能够召回匹配的金融指标");
        System.out.println("【召回指标】: " + matchedMetrics);

        // 验证命中近1年回报
        boolean found1yReturn = false;
        for (JsonNode m : matchedMetrics) {
            if ("f_return_1y".equals(m.path("mnemonic").asText())) {
                found1yReturn = true;
                break;
            }
        }
        assertTrue(found1yReturn, "查询'近1年收益'必须在前5条中准确召回 f_return_1y 指标");

        JsonNode matchedSectors = root.path("matchedSectors");
        assertFalse(matchedSectors.isEmpty(), "必须能够召回匹配的板块分类");
        System.out.println("【召回板块】: " + matchedSectors);

        // 验证板块叶子节点展开
        for (JsonNode s : matchedSectors) {
            JsonNode leafIds = s.path("expandedLeafIds");
            assertTrue(leafIds.isArray() && !leafIds.isEmpty(), "板块必须包含展开的叶子 ID");
        }
    }

    @Test
    @DisplayName("真实链路：指标释义与同族推荐 (explain_metric)")
    void testLiveExplainMetric() throws Exception {
        String jsonResult = ragTool.explainMetric("f_return_1y");
        assertNotNull(jsonResult);

        JsonNode root = objectMapper.readTree(jsonResult);
        assertEquals("f_return_1y", root.path("mnemonic").asText());
        assertEquals("近1年回报", root.path("indexName").asText());
        assertEquals("收益指标", root.path("parentName").asText());

        JsonNode siblings = root.path("siblingMetrics");
        assertFalse(siblings.isEmpty(), "基于 Neo4j 拓扑必须能推荐同为'收益指标'分类下的兄弟指标");
        System.out.println("【f_return_1y 同族指标推荐】: " + siblings);
    }

    @Test
    @DisplayName("真实链路：板块向下多层穿透展开叶子节点 (expand_sector)")
    void testLiveExpandSector() throws Exception {
        // 1000009160000000 是 "中国上市ETF"
        String jsonResult = ragTool.expandSector("1000009160000000");
        assertNotNull(jsonResult);

        JsonNode root = objectMapper.readTree(jsonResult);
        assertEquals("1000009160000000", root.path("sectorId").asText());
        int leafCount = root.path("leafCount").asInt();
        assertTrue(leafCount > 1, "中国上市ETF 下应包含多个可交易的底层叶子分类");
        System.out.println("【中国上市ETF 底层展开叶子数】: " + leafCount);
    }
}
