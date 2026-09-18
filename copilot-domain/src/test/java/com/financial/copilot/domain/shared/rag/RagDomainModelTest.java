package com.financial.copilot.domain.shared.rag;

import com.financial.copilot.domain.shared.rag.entity.RagFundMetric;
import com.financial.copilot.domain.shared.rag.entity.RagFundSector;
import com.financial.copilot.domain.shared.rag.entity.SchemaRecallResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RagDomainModelTest {

    @Test
    @DisplayName("验证 RagFundMetric 实体创建与属性访问")
    void testRagFundMetricCreation() {
        float[] sampleVec = new float[1024];
        sampleVec[0] = 0.5f;

        RagFundMetric metric = RagFundMetric.builder()
                .mnemonic("f_return_1y")
                .indexName("近1年回报")
                .parentName("收益指标")
                .description("近1年回报率")
                .embeddingText("近1年回报 f_return_1y")
                .embedding(sampleVec)
                .supportedUsage(List.of("filter", "sort"))
                .applicableProducts("1001000")
                .aliases(List.of("近一年收益", "近一年回报"))
                .version(3)
                .enabled(true)
                .build();

        assertEquals("f_return_1y", metric.getMnemonic());
        assertEquals("近1年回报", metric.getIndexName());
        assertEquals("收益指标", metric.getParentName());
        assertTrue(metric.isEnabled());
        assertEquals(2, metric.getAliases().size());
        assertEquals(1024, metric.getEmbedding().length);
    }

    @Test
    @DisplayName("验证 RagFundSector 实体创建与属性访问")
    void testRagFundSectorCreation() {
        float[] sampleVec = new float[1024];
        RagFundSector sector = RagFundSector.builder()
                .sectorId("1000009160000000")
                .parentId("1000019220000000")
                .name("中国上市ETF")
                .nameEn("China Listed ETF")
                .aliases(List.of("ETF", "上市ETF"))
                .description("中国上市ETF基金分类")
                .embeddingText("中国上市ETF ETF")
                .embedding(sampleVec)
                .isLeaf(false)
                .elementType(6)
                .treeLevel(1)
                .fullPathNames("内地公募基金 > 中国上市ETF")
                .enabled(true)
                .build();

        assertEquals("1000009160000000", sector.getSectorId());
        assertEquals("中国上市ETF", sector.getName());
        assertFalse(sector.isLeaf());
        assertEquals(1, sector.getTreeLevel());
        assertEquals("内地公募基金 > 中国上市ETF", sector.getFullPathNames());
    }

    @Test
    @DisplayName("验证 SchemaRecallResult 联合召回聚合结果")
    void testSchemaRecallResult() {
        RagFundMetric metric = RagFundMetric.builder()
                .mnemonic("f_return_1y")
                .indexName("近1年回报")
                .build();

        RagFundSector sector = RagFundSector.builder()
                .sectorId("1000009160000000")
                .name("中国上市ETF")
                .build();

        SchemaRecallResult result = SchemaRecallResult.builder()
                .query("近1年收益大于20%的ETF")
                .matchedMetrics(List.of(new SchemaRecallResult.MetricMatch(metric, 0.95, "收益指标")))
                .matchedSectors(List.of(new SchemaRecallResult.SectorMatch(sector, 0.91, List.of("1000009161000000"))))
                .build();

        assertEquals("近1年收益大于20%的ETF", result.getQuery());
        assertEquals(1, result.getMatchedMetrics().size());
        assertEquals(1, result.getMatchedSectors().size());
        assertEquals(0.95, result.getMatchedMetrics().get(0).score());
        assertEquals("1000009161000000", result.getMatchedSectors().get(0).expandedLeafIds().get(0));
    }
}
