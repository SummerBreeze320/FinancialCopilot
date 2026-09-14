package com.financial.copilot.data.rag.parser;

import com.financial.copilot.domain.rag.entity.RagFundMetric;
import com.financial.copilot.domain.rag.entity.RagFundSector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MetricSectorDataParserTest {

    private final MetricSectorDataParser parser = new MetricSectorDataParser();

    @Test
    @DisplayName("测试从类路径加载指标样例 JSON 并解析为领域实体")
    void testParseMetricsStream() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/data/sample-metrics.json")) {
            assertNotNull(is, "测试资源 /data/sample-metrics.json 必须存在");

            List<RagFundMetric> metrics = parser.parseMetrics(is);
            assertNotNull(metrics);
            assertEquals(4, metrics.size(), "样例指标数量为 4");

            Map<String, RagFundMetric> metricMap = metrics.stream()
                    .collect(Collectors.toMap(RagFundMetric::getMnemonic, m -> m));

            RagFundMetric return1y = metricMap.get("f_return_1y");
            assertNotNull(return1y, "必须包含 f_return_1y");
            assertEquals("近1年回报", return1y.getIndexName());
            assertEquals("收益指标", return1y.getParentName());
            assertTrue(return1y.getSupportedUsage().contains("filter"));
            assertTrue(return1y.getAliases().contains("近一年收益"));

            RagFundMetric maxDownside = metricMap.get("f_risk_maxdownside");
            assertNotNull(maxDownside, "必须包含 f_risk_maxdownside");
            assertEquals("最大回撤", maxDownside.getIndexName());
            assertEquals("风险指标", maxDownside.getParentName());
        }
    }

    @Test
    @DisplayName("测试从类路径加载板块样例 JSON 并验证树形层级展开与祖先面包屑计算")
    void testParseSectorsStream() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/data/sample-sectors.json")) {
            assertNotNull(is, "测试资源 /data/sample-sectors.json 必须存在");

            List<RagFundSector> sectors = parser.parseSectors(is);
            assertNotNull(sectors);
            assertEquals(4, sectors.size(), "样例板块数量为 4");

            Map<String, RagFundSector> sectorMap = sectors.stream()
                    .collect(Collectors.toMap(RagFundSector::getSectorId, s -> s));

            // 检查根节点
            RagFundSector root = sectorMap.get("1000019220000000");
            assertNotNull(root, "根节点 内地公募基金 必须存在");
            assertEquals("内地公募基金", root.getName());
            assertEquals(0, root.getTreeLevel(), "根节点深度必须为 0");
            assertEquals("内地公募基金", root.getFullPathNames());
            assertFalse(root.isLeaf(), "根节点不应是叶子节点");

            // 检查第一级子节点
            RagFundSector etf = sectorMap.get("1000009160000000");
            assertNotNull(etf, "中国上市ETF 节点必须存在");
            assertEquals("中国上市ETF", etf.getName());
            assertEquals(1, etf.getTreeLevel(), "中国上市ETF 深度必须为 1");
            assertEquals("内地公募基金 > 中国上市ETF", etf.getFullPathNames());

            // 检查叶子节点
            RagFundSector stockEtf = sectorMap.get("1000009161000000");
            assertNotNull(stockEtf);
            assertTrue(stockEtf.isLeaf());
            assertEquals(2, stockEtf.getTreeLevel());
            assertEquals("内地公募基金 > 中国上市ETF > 股票型ETF", stockEtf.getFullPathNames());
        }
    }
}
