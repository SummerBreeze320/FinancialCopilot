package com.financial.copilot.data.rag.adapter;

import com.financial.copilot.domain.rag.entity.RagFundMetric;
import com.financial.copilot.domain.rag.entity.RagFundSector;
import com.financial.copilot.domain.rag.port.RagGraphPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * <h1>基于 Neo4j 5.x 的基金指标与板块拓扑图适配器</h1>
 * <p>
 * 实现领域层 {@link RagGraphPort} 接口：
 * <ul>
 *   <li>同步板块分类树：创建 <code>:FundSector</code> 节点及 <code>[:PARENT_OF]</code> 有向层次关系；</li>
 *   <li>同步指标分类网络：创建 <code>:MetricCategory</code> 与 <code>:FundMetric</code> 及 <code>[:CONTAINS_METRIC]</code> 关联；</li>
 *   <li>多跳递归下钻：利用 Cypher <code>[:PARENT_OF*1..4]</code> 将任一中间父级板块穿透展开为具体底层可交易的叶子板块 ID 清单；</li>
 *   <li>同族指标发现：在知识图谱中根据指标分类推荐关联的同族评价指标。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "copilot.neo4j", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Neo4jRagGraphAdapter implements RagGraphPort {

    private final Neo4jClient neo4jClient;

    /**
     * 同步全量公募板块分类树至 Neo4j 图数据库
     *
     * @param sectors 板块领域实体列表
     */
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

    /**
     * 同步全量公募指标及其分类体系至 Neo4j 图数据库
     *
     * @param metrics 指标领域实体列表
     */
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

    /**
     * 向下递归穿透指定板块树，获取其覆盖的所有底层叶子板块编码（供下游 SQL IN 过滤）
     *
     * @param sectorId 目标板块编码
     * @return 叶子板块 ID 列表；若该板块本身为叶子，则返回自身 ID
     */
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

    /**
     * 查询同分类下的相关指标（同族推荐）
     *
     * @param mnemonic 指标助记符
     * @param limit    返回上限
     * @return 同分类指标中文名称列表
     */
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

    /**
     * 统计当前图谱中板块分类节点总数
     *
     * @return 板块节点数
     */
    @Override
    public long countSectorNodes() {
        return neo4jClient.query("MATCH (s:FundSector) RETURN count(s) AS total")
                .fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("total").asLong())
                .one()
                .orElse(0L);
    }

    /**
     * 统计当前图谱中公募指标节点总数
     *
     * @return 指标节点数
     */
    @Override
    public long countMetricNodes() {
        return neo4jClient.query("MATCH (m:FundMetric) RETURN count(m) AS total")
                .fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("total").asLong())
                .one()
                .orElse(0L);
    }
}
