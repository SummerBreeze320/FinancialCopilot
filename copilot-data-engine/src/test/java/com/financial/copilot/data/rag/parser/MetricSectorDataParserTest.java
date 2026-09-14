package com.financial.copilot.data.rag.parser;

import com.financial.copilot.domain.rag.entity.RagFundMetric;
import com.financial.copilot.domain.rag.entity.RagFundSector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MetricSectorDataParserTest {

    private final MetricSectorDataParser parser = new MetricSectorDataParser();

    @Test
    @DisplayName("测试内存模拟 JSON 数据解析指标为领域实体")
    void testParseMetricsStream() throws Exception {
        String mockMetricsJson = """
            [
              {
                "mnemonic": "f_return_1y",
                "index_name": "近1年回报",
                "parent_name": "收益指标",
                "description": "近1年回报率",
                "supported_usage": ["filter", "sort"],
                "aliases": ["近一年收益", "近一年回报"]
              },
              {
                "mnemonic": "f_risk_maxdownside",
                "index_name": "最大回撤",
                "parent_name": "风险指标",
                "description": "最大回撤",
                "supported_usage": ["filter", "sort"]
              }
            ]
            """;

        try (InputStream is = new ByteArrayInputStream(mockMetricsJson.getBytes(StandardCharsets.UTF_8))) {
            List<RagFundMetric> metrics = parser.parseMetrics(is);
            assertNotNull(metrics);
            assertEquals(2, metrics.size());

            Map<String, RagFundMetric> metricMap = metrics.stream()
                    .collect(Collectors.toMap(RagFundMetric::getMnemonic, m -> m));

            RagFundMetric return1y = metricMap.get("f_return_1y");
            assertNotNull(return1y);
            assertEquals("近1年回报", return1y.getIndexName());
            assertEquals("收益指标", return1y.getParentName());
            assertTrue(return1y.getSupportedUsage().contains("filter"));
            assertTrue(return1y.getAliases().contains("近一年收益"));
        }
    }

    @Test
    @DisplayName("测试内存模拟 JSON 数据验证板块树形层级展开与祖先面包屑计算")
    void testParseSectorsStream() throws Exception {
        String mockSectorsJson = """
            [
              {
                "source_sector_id": "1000019220000000",
                "parent_id": null,
                "name": "内地公募基金",
                "is_leaf": false
              },
              {
                "source_sector_id": "1000009160000000",
                "parent_id": "1000019220000000",
                "name": "中国上市ETF",
                "is_leaf": false
              },
              {
                "source_sector_id": "1000009161000000",
                "parent_id": "1000009160000000",
                "name": "股票型ETF",
                "is_leaf": true
              }
            ]
            """;

        try (InputStream is = new ByteArrayInputStream(mockSectorsJson.getBytes(StandardCharsets.UTF_8))) {
            List<RagFundSector> sectors = parser.parseSectors(is);
            assertNotNull(sectors);
            assertEquals(3, sectors.size());

            Map<String, RagFundSector> sectorMap = sectors.stream()
                    .collect(Collectors.toMap(RagFundSector::getSectorId, s -> s));

            // 根节点
            RagFundSector root = sectorMap.get("1000019220000000");
            assertNotNull(root);
            assertEquals(0, root.getTreeLevel());
            assertEquals("内地公募基金", root.getFullPathNames());
            assertFalse(root.isLeaf());

            // 1 级节点
            RagFundSector etf = sectorMap.get("1000009160000000");
            assertNotNull(etf);
            assertEquals(1, etf.getTreeLevel());
            assertEquals("内地公募基金 > 中国上市ETF", etf.getFullPathNames());

            // 2 级叶子节点
            RagFundSector stockEtf = sectorMap.get("1000009161000000");
            assertNotNull(stockEtf);
            assertTrue(stockEtf.isLeaf());
            assertEquals(2, stockEtf.getTreeLevel());
            assertEquals("内地公募基金 > 中国上市ETF > 股票型ETF", stockEtf.getFullPathNames());
        }
    }
}
