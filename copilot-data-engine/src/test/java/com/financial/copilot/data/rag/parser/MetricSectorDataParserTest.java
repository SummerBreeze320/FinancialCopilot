package com.financial.copilot.data.rag.parser;

import com.financial.copilot.domain.rag.entity.RagFundMetric;
import com.financial.copilot.domain.rag.entity.RagFundSector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MetricSectorDataParserTest {

    private final MetricSectorDataParser parser = new MetricSectorDataParser();

    private File resolveFile(String relativePath) {
        File file = new File(relativePath);
        if (file.exists()) {
            return file;
        }
        File parentRelative = new File(".." + File.separator + relativePath);
        if (parentRelative.exists()) {
            return parentRelative;
        }
        return file;
    }

    @Test
    @DisplayName("测试解析真实 docs/temp/metrics.json 文件")
    void testParseMetricsFile() throws Exception {
        File metricsFile = resolveFile("docs/temp/metrics.json");
        assertTrue(metricsFile.exists(), "docs/temp/metrics.json 文件必须存在: path=" + metricsFile.getAbsolutePath());

        try (InputStream is = new FileInputStream(metricsFile)) {
            List<RagFundMetric> metrics = parser.parseMetrics(is);
            assertNotNull(metrics);
            assertEquals(169, metrics.size(), "指标总数量必须为 169 项");

            // 检查特定核心指标
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
    @DisplayName("测试解析真实 docs/temp/sectors.json 文件并验证树形层级展开")
    void testParseSectorsFile() throws Exception {
        File sectorsFile = resolveFile("docs/temp/sectors.json");
        assertTrue(sectorsFile.exists(), "docs/temp/sectors.json 文件必须存在: path=" + sectorsFile.getAbsolutePath());

        try (InputStream is = new FileInputStream(sectorsFile)) {
            List<RagFundSector> sectors = parser.parseSectors(is);
            assertNotNull(sectors);
            assertEquals(1712, sectors.size(), "板块总数量必须为 1712 项");

            Map<String, RagFundSector> sectorMap = sectors.stream()
                    .collect(Collectors.toMap(RagFundSector::getSectorId, s -> s));

            // 检查根节点
            RagFundSector root = sectorMap.get("1000019220000000");
            assertNotNull(root, "根节点 内地公募基金 必须存在");
            assertEquals("内地公募基金", root.getName());
            assertEquals(0, root.getTreeLevel(), "根节点深度必须为 0");
            assertEquals("内地公募基金", root.getFullPathNames());
            assertFalse(root.isLeaf(), "根节点不应是叶子节点");

            // 检查特定下级节点
            RagFundSector etf = sectorMap.get("1000009160000000");
            assertNotNull(etf, "中国上市ETF 节点必须存在");
            assertEquals("中国上市ETF", etf.getName());
            assertEquals(1, etf.getTreeLevel(), "中国上市ETF 深度必须为 1");
            assertEquals("内地公募基金 > 中国上市ETF", etf.getFullPathNames());

            // 检查叶子节点
            long leafCount = sectors.stream().filter(RagFundSector::isLeaf).count();
            assertTrue(leafCount > 1500, "叶子节点数量应大于 1500 项");
        }
    }
}
