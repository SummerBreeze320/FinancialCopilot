package com.financial.copilot.data.rag.adapter;

import com.financial.copilot.domain.rag.entity.RagFundMetric;
import com.financial.copilot.domain.rag.entity.RagFundSector;
import com.financial.copilot.domain.rag.port.RagGraphPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * <h1>Neo4j 5.x 知识图谱拓扑适配器</h1>
 * 管理板块分类树与指标分类网络，执行叶子下钻展开与同族指标发现。
 */
@Slf4j
@Repository
@ConditionalOnProperty(prefix = "copilot.neo4j", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Neo4jRagGraphAdapter implements RagGraphPort {

    private final Neo4jClient neo4jClient;

    public Neo4jRagGraphAdapter(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Override
    public void syncSectorGraph(List<RagFundSector> sectors) {
        if (sectors == null || sectors.isEmpty()) return;

        // 1. 创建唯一性约束与索引
        neo4jClient.query("CREATE CONSTRAINT cstr_sector_id IF NOT EXISTS FOR (s:FundSector) REQUIRE s.sector_id IS UNIQUE").run();
        neo4jClient.query("CREATE INDEX idx_sector_name IF NOT EXISTS FOR (s:FundSector) ON (s.name)").run();

        // 2. 分批合并板块节点
        int batchSize = 200;
        for (int i = 0; i < sectors.size(); i += batchSize) {
            int end = Math.min(i + batchSize, sectors.size());
            List<Map<String, Object>> batch = new ArrayList<>();
            for (RagFundSector s : sectors.subList(i, end)) {
                Map<String, Object> map = new HashMap<>();
                map.put("sectorId", s.getSectorId());
                map.put("name", s.getName());
                map.put("isLeaf", s.isLeaf());
                map.put("elementType", s.getElementType() != null ? s.getElementType() : 6);
                map.put("treeLevel", s.getTreeLevel() != null ? s.getTreeLevel() : 0);
                map.put("fullPath", s.getFullPathNames() != null ? s.getFullPathNames() : s.getName());
                map.put("parentId", s.getParentId());
                batch.add(map);
            }

            String nodeCypher = """
                UNWIND $batch AS item
                MERGE (s:FundSector {sector_id: item.sectorId})
                SET s.name = item.name,
                    s.is_leaf = item.isLeaf,
                    s.element_type = item.elementType,
                    s.tree_level = item.treeLevel,
                    s.full_path = item.fullPath
                """;
            neo4jClient.query(nodeCypher).bind(batch).to("batch").run();

            // 3. 关联父子关系
            String relCypher = """
                UNWIND $batch AS item
                WITH item WHERE item.parentId IS NOT NULL AND item.parentId <> ''
                MATCH (p:FundSector {sector_id: item.parentId})
                MATCH (c:FundSector {sector_id: item.sectorId})
                MERGE (p)-[:PARENT_OF]->(c)
                """;
            neo4jClient.query(relCypher).bind(batch).to("batch").run();
        }

        log.info("[RAG-NEO4J] 成功同步板块分类树节点与拓扑关系: count={}", sectors.size());
    }

    @Override
    public void syncMetricGraph(List<RagFundMetric> metrics) {
        if (metrics == null || metrics.isEmpty()) return;

        // 1. 创建约束与索引
        neo4jClient.query("CREATE CONSTRAINT cstr_metric_mnemonic IF NOT EXISTS FOR (m:FundMetric) REQUIRE m.mnemonic IS UNIQUE").run();
        neo4jClient.query("CREATE CONSTRAINT cstr_metric_cat IF NOT EXISTS FOR (c:MetricCategory) REQUIRE c.name IS UNIQUE").run();

        // 2. 分批合并分类与指标节点及关系
        int batchSize = 100;
        for (int i = 0; i < metrics.size(); i += batchSize) {
            int end = Math.min(i + batchSize, metrics.size());
            List<Map<String, Object>> batch = new ArrayList<>();
            for (RagFundMetric m : metrics.subList(i, end)) {
                Map<String, Object> map = new HashMap<>();
                map.put("mnemonic", m.getMnemonic());
                map.put("name", m.getIndexName());
                map.put("parentName", m.getParentName());
                map.put("description", m.getDescription() != null ? m.getDescription() : "");
                map.put("supportedUsage", m.getSupportedUsage() != null ? m.getSupportedUsage() : List.of());
                batch.add(map);
            }

            String cypher = """
                UNWIND $batch AS item
                MERGE (cat:MetricCategory {name: item.parentName})
                MERGE (m:FundMetric {mnemonic: item.mnemonic})
                SET m.name = item.name,
                    m.description = item.description,
                    m.supported_usage = item.supportedUsage
                MERGE (cat)-[:CONTAINS_METRIC]->(m)
                """;
            neo4jClient.query(cypher).bind(batch).to("batch").run();
        }

        log.info("[RAG-NEO4J] 成功同步指标分类与指标节点: count={}", metrics.size());
    }

    @Override
    public List<String> expandLeafSectors(String sectorId) {
        if (sectorId == null || sectorId.isBlank()) return Collections.emptyList();

        String cypher = """
            MATCH (p:FundSector {sector_id: $sectorId})
            OPTIONAL MATCH (p)-[:PARENT_OF*1..4]->(leaf:FundSector)
            WHERE leaf.is_leaf = true
            RETURN p.is_leaf AS isSelfLeaf, collect(DISTINCT leaf.sector_id) AS leafIds
            """;

        Collection<Map<String, Object>> results = neo4jClient.query(cypher)
                .bind(sectorId).to("sectorId")
                .fetch().all();

        if (results.isEmpty()) {
            return List.of(sectorId);
        }

        Map<String, Object> row = results.iterator().next();
        Boolean isSelfLeaf = (Boolean) row.get("isSelfLeaf");
        @SuppressWarnings("unchecked")
        List<String> leafIds = (List<String>) row.get("leafIds");

        if (Boolean.TRUE.equals(isSelfLeaf) || leafIds == null || leafIds.isEmpty()) {
            return List.of(sectorId);
        }
        return leafIds;
    }

    @Override
    public List<String> findMetricSiblings(String mnemonic, int limit) {
        if (mnemonic == null || mnemonic.isBlank()) return Collections.emptyList();

        String cypher = """
            MATCH (m:FundMetric {mnemonic: $mnemonic})<-[:CONTAINS_METRIC]-(cat:MetricCategory)-[:CONTAINS_METRIC]->(sib:FundMetric)
            WHERE sib.mnemonic <> $mnemonic
            RETURN sib.name AS name
            LIMIT $limit
            """;

        Collection<String> names = neo4jClient.query(cypher)
                .bind(mnemonic).to("mnemonic")
                .bind(Math.max(1, limit)).to("limit")
                .fetchAs(String.class)
                .mappedBy((typeSystem, record) -> record.get("name").asString())
                .all();

        return new ArrayList<>(names);
    }

    @Override
    public long countSectorNodes() {
        return neo4jClient.query("MATCH (s:FundSector) RETURN count(s) AS total")
                .fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("total").asLong())
                .one()
                .orElse(0L);
    }

    @Override
    public long countMetricNodes() {
        return neo4jClient.query("MATCH (m:FundMetric) RETURN count(m) AS total")
                .fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("total").asLong())
                .one()
                .orElse(0L);
    }
}
