package com.financial.copilot.agent.tools.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.shared.rag.entity.RagFundMetric;
import com.financial.copilot.domain.shared.rag.entity.RagFundSector;
import com.financial.copilot.domain.shared.rag.entity.SchemaRecallResult;
import com.financial.copilot.domain.shared.rag.port.RagEmbeddingPort;
import com.financial.copilot.domain.shared.rag.port.RagGraphPort;
import com.financial.copilot.domain.shared.rag.port.RagSchemaPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class FinancialSchemaRagToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("测试 Agent Tool matchMetricsAndSectors 联合召回输出格式")
    void testMatchMetricsAndSectors() throws Exception {
        RagEmbeddingPort embeddingPort = Mockito.mock(RagEmbeddingPort.class);
        RagSchemaPort schemaPort = Mockito.mock(RagSchemaPort.class);
        RagGraphPort graphPort = Mockito.mock(RagGraphPort.class);

        when(embeddingPort.embed(anyString())).thenReturn(new float[1024]);

        RagFundMetric metric = RagFundMetric.builder()
                .mnemonic("f_return_1y")
                .indexName("近1年回报")
                .parentName("收益指标")
                .description("近1年回报率")
                .supportedUsage(List.of("filter", "sort"))
                .build();

        RagFundSector sector = RagFundSector.builder()
                .sectorId("1000009160000000")
                .name("中国上市ETF")
                .fullPathNames("内地公募基金 > 中国上市ETF")
                .isLeaf(false)
                .build();

        when(schemaPort.searchMetrics(anyString(), any(), anyInt()))
                .thenReturn(List.of(new SchemaRecallResult.MetricMatch(metric, 0.95, "收益指标")));

        when(schemaPort.searchSectors(anyString(), any(), anyInt()))
                .thenReturn(List.of(new SchemaRecallResult.SectorMatch(sector, 0.92, List.of())));

        when(graphPort.expandLeafSectors("1000009160000000"))
                .thenReturn(List.of("1000009161000000", "1000009162000000"));

        FinancialSchemaRagTool tool = new FinancialSchemaRagTool(
                embeddingPort, schemaPort, Optional.of(graphPort), objectMapper
        );

        String jsonOutput = tool.matchMetricsAndSectors("近1年收益大于20%的ETF", 5);
        assertNotNull(jsonOutput);

        JsonNode root = objectMapper.readTree(jsonOutput);
        assertEquals("近1年收益大于20%的ETF", root.get("query").asText());

        JsonNode metricsNode = root.get("matchedMetrics");
        assertTrue(metricsNode.isArray());
        assertEquals(1, metricsNode.size());
        assertEquals("f_return_1y", metricsNode.get(0).get("mnemonic").asText());
        assertEquals("近1年回报", metricsNode.get(0).get("name").asText());

        JsonNode sectorsNode = root.get("matchedSectors");
        assertTrue(sectorsNode.isArray());
        assertEquals(1, sectorsNode.size());
        assertEquals("1000009160000000", sectorsNode.get(0).get("sectorId").asText());

        JsonNode leafIds = sectorsNode.get(0).get("expandedLeafIds");
        assertTrue(leafIds.isArray());
        assertEquals(2, leafIds.size());
    }

    @Test
    @DisplayName("测试 Agent Tool explainMetric 指标解释与同类推荐")
    void testExplainMetric() throws Exception {
        RagEmbeddingPort embeddingPort = Mockito.mock(RagEmbeddingPort.class);
        RagSchemaPort schemaPort = Mockito.mock(RagSchemaPort.class);
        RagGraphPort graphPort = Mockito.mock(RagGraphPort.class);

        RagFundMetric sharpe = RagFundMetric.builder()
                .mnemonic("f_risk_sharpe")
                .indexName("Sharpe")
                .parentName("风险指标")
                .description("夏普比率")
                .build();

        when(schemaPort.findMetricByMnemonic("f_risk_sharpe")).thenReturn(Optional.of(sharpe));
        when(graphPort.findMetricSiblings("f_risk_sharpe", 5)).thenReturn(List.of("最大回撤", "年化波动率"));

        FinancialSchemaRagTool tool = new FinancialSchemaRagTool(
                embeddingPort, schemaPort, Optional.of(graphPort), objectMapper
        );

        String jsonOutput = tool.explainMetric("f_risk_sharpe");
        JsonNode root = objectMapper.readTree(jsonOutput);
        assertEquals("f_risk_sharpe", root.get("mnemonic").asText());
        assertEquals("Sharpe", root.get("indexName").asText());
        assertEquals("风险指标", root.get("parentName").asText());
        assertTrue(root.get("siblingMetrics").isArray());
        assertEquals(2, root.get("siblingMetrics").size());
    }
}
