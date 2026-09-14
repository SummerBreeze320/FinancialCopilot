package com.financial.copilot.data.rag.adapter;

import com.financial.copilot.domain.rag.entity.RagFundMetric;
import com.financial.copilot.domain.rag.entity.RagFundSector;
import com.financial.copilot.domain.rag.entity.SchemaRecallResult;
import com.financial.copilot.domain.rag.port.RagSchemaPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

/**
 * <h1>PostgreSQL 16 + PGVector 存储适配器</h1>
 * 支持三路混合召回（向量相似度 + 词面三元组 + 精确代码匹配）。
 */
@Slf4j
@Repository
public class PostgresRagSchemaAdapter implements RagSchemaPort {

    private final JdbcTemplate jdbcTemplate;

    public PostgresRagSchemaAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private String formatPgVector(float[] vec) {
        if (vec == null || vec.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder(vec.length * 9 + 2);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private List<String> sqlArrayToList(Array array) throws SQLException {
        if (array == null) return Collections.emptyList();
        String[] arr = (String[]) array.getArray();
        return arr != null ? Arrays.asList(arr) : Collections.emptyList();
    }

    private final RowMapper<RagFundMetric> metricRowMapper = (rs, rowNum) -> RagFundMetric.builder()
            .mnemonic(rs.getString("mnemonic"))
            .indexName(rs.getString("index_name"))
            .parentName(rs.getString("parent_name"))
            .description(rs.getString("description"))
            .embeddingText(rs.getString("embedding_text"))
            .sourceIndicatorId(rs.getObject("source_indicator_id", Long.class))
            .supportedUsage(sqlArrayToList(rs.getArray("supported_usage")))
            .applicableProducts(rs.getString("applicable_products"))
            .aliases(sqlArrayToList(rs.getArray("aliases")))
            .version(rs.getInt("version"))
            .enabled(rs.getBoolean("enabled"))
            .createdAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null)
            .build();

    private final RowMapper<RagFundSector> sectorRowMapper = (rs, rowNum) -> RagFundSector.builder()
            .sectorId(rs.getString("sector_id"))
            .parentId(rs.getString("parent_id"))
            .name(rs.getString("name"))
            .nameEn(rs.getString("name_en"))
            .aliases(sqlArrayToList(rs.getArray("aliases")))
            .description(rs.getString("description"))
            .embeddingText(rs.getString("embedding_text"))
            .isLeaf(rs.getBoolean("is_leaf"))
            .elementType(rs.getInt("element_type"))
            .treeLevel(rs.getInt("tree_level"))
            .fullPathNames(rs.getString("full_path_names"))
            .enabled(rs.getBoolean("enabled"))
            .createdAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null)
            .build();

    @Override
    public void upsertMetrics(List<RagFundMetric> metrics) {
        if (metrics == null || metrics.isEmpty()) return;

        String sql = """
            INSERT INTO rag_fund_metric (
                mnemonic, index_name, parent_name, description, embedding_text,
                embedding, source_indicator_id, supported_usage, applicable_products, aliases, version, enabled
            ) VALUES (?, ?, ?, ?, ?, ?::vector, ?, ?::varchar[], ?, ?::text[], ?, ?)
            ON CONFLICT (mnemonic) DO UPDATE SET
                index_name = EXCLUDED.index_name,
                parent_name = EXCLUDED.parent_name,
                description = EXCLUDED.description,
                embedding_text = EXCLUDED.embedding_text,
                embedding = EXCLUDED.embedding,
                source_indicator_id = EXCLUDED.source_indicator_id,
                supported_usage = EXCLUDED.supported_usage,
                applicable_products = EXCLUDED.applicable_products,
                aliases = EXCLUDED.aliases,
                version = EXCLUDED.version,
                enabled = EXCLUDED.enabled;
            """;

        jdbcTemplate.batchUpdate(sql, metrics, 50, (PreparedStatement ps, RagFundMetric m) -> {
            ps.setString(1, m.getMnemonic());
            ps.setString(2, m.getIndexName());
            ps.setString(3, m.getParentName());
            ps.setString(4, m.getDescription());
            ps.setString(5, m.getEmbeddingText());
            ps.setString(6, formatPgVector(m.getEmbedding()));
            ps.setObject(7, m.getSourceIndicatorId());

            Array usageArr = ps.getConnection().createArrayOf("varchar",
                    m.getSupportedUsage() != null ? m.getSupportedUsage().toArray() : new String[0]);
            ps.setArray(8, usageArr);

            ps.setString(9, m.getApplicableProducts());

            Array aliasArr = ps.getConnection().createArrayOf("text",
                    m.getAliases() != null ? m.getAliases().toArray() : new String[0]);
            ps.setArray(10, aliasArr);

            ps.setInt(11, m.getVersion() != null ? m.getVersion() : 1);
            ps.setBoolean(12, m.isEnabled());
        });

        log.info("[RAG-POSTGRES] 批量 upsert 指标完成: count={}", metrics.size());
    }

    @Override
    public void upsertSectors(List<RagFundSector> sectors) {
        if (sectors == null || sectors.isEmpty()) return;

        String sql = """
            INSERT INTO rag_fund_sector (
                sector_id, parent_id, name, name_en, aliases, description,
                embedding_text, embedding, is_leaf, element_type, tree_level, full_path_names, enabled
            ) VALUES (?, ?, ?, ?, ?::text[], ?, ?, ?::vector, ?, ?, ?, ?, ?)
            ON CONFLICT (sector_id) DO UPDATE SET
                parent_id = EXCLUDED.parent_id,
                name = EXCLUDED.name,
                name_en = EXCLUDED.name_en,
                aliases = EXCLUDED.aliases,
                description = EXCLUDED.description,
                embedding_text = EXCLUDED.embedding_text,
                embedding = EXCLUDED.embedding,
                is_leaf = EXCLUDED.is_leaf,
                element_type = EXCLUDED.element_type,
                tree_level = EXCLUDED.tree_level,
                full_path_names = EXCLUDED.full_path_names,
                enabled = EXCLUDED.enabled;
            """;

        jdbcTemplate.batchUpdate(sql, sectors, 100, (PreparedStatement ps, RagFundSector s) -> {
            ps.setString(1, s.getSectorId());
            ps.setString(2, s.getParentId());
            ps.setString(3, s.getName());
            ps.setString(4, s.getNameEn());

            Array aliasArr = ps.getConnection().createArrayOf("text",
                    s.getAliases() != null ? s.getAliases().toArray() : new String[0]);
            ps.setArray(5, aliasArr);

            ps.setString(6, s.getDescription());
            ps.setString(7, s.getEmbeddingText());
            ps.setString(8, formatPgVector(s.getEmbedding()));
            ps.setBoolean(9, s.isLeaf());
            ps.setInt(10, s.getElementType() != null ? s.getElementType() : 6);
            ps.setInt(11, s.getTreeLevel() != null ? s.getTreeLevel() : 0);
            ps.setString(12, s.getFullPathNames());
            ps.setBoolean(13, s.isEnabled());
        });

        log.info("[RAG-POSTGRES] 批量 upsert 板块完成: count={}", sectors.size());
    }

    @Override
    public List<SchemaRecallResult.MetricMatch> searchMetrics(String query, float[] queryVec, int topK) {
        String vecStr = formatPgVector(queryVec);
        int limit = Math.max(1, topK);

        String sql = """
            SELECT mnemonic, index_name, parent_name, description, embedding_text,
                   source_indicator_id, supported_usage, applicable_products, aliases, version, enabled, created_at,
                   COALESCE(1 - (embedding <=> ?::vector), 0.0) AS vec_score,
                   similarity(index_name, ?) AS txt_score,
                   (CASE WHEN mnemonic ILIKE ? OR index_name = ? OR ? = ANY(aliases) THEN 1.0 ELSE 0.0 END) AS exact_score
            FROM rag_fund_metric
            WHERE enabled = true
            ORDER BY (
                COALESCE(1 - (embedding <=> ?::vector), 0.0) * 0.7 +
                similarity(index_name, ?) * 0.3 +
                (CASE WHEN mnemonic ILIKE ? OR index_name = ? OR ? = ANY(aliases) THEN 0.5 ELSE 0.0 END)
            ) DESC
            LIMIT ?;
            """;

        String qClean = query != null ? query.trim() : "";
        return jdbcTemplate.query(sql, ps -> {
            ps.setString(1, vecStr);
            ps.setString(2, qClean);
            ps.setString(3, qClean);
            ps.setString(4, qClean);
            ps.setString(5, qClean);
            ps.setString(6, vecStr);
            ps.setString(7, qClean);
            ps.setString(8, qClean);
            ps.setString(9, qClean);
            ps.setString(10, qClean);
            ps.setInt(11, limit);
        }, (rs, rowNum) -> {
            RagFundMetric metric = metricRowMapper.mapRow(rs, rowNum);
            double vScore = rs.getDouble("vec_score");
            double tScore = rs.getDouble("txt_score");
            double eScore = rs.getDouble("exact_score");
            double finalScore = vScore * 0.7 + tScore * 0.3 + eScore * 0.5;
            return new SchemaRecallResult.MetricMatch(metric, finalScore, metric.getParentName());
        });
    }

    @Override
    public List<SchemaRecallResult.SectorMatch> searchSectors(String query, float[] queryVec, int topK) {
        String vecStr = formatPgVector(queryVec);
        int limit = Math.max(1, topK);

        String sql = """
            SELECT sector_id, parent_id, name, name_en, aliases, description,
                   embedding_text, is_leaf, element_type, tree_level, full_path_names, enabled, created_at,
                   COALESCE(1 - (embedding <=> ?::vector), 0.0) AS vec_score,
                   similarity(name, ?) AS txt_score,
                   (CASE WHEN name = ? OR ? = ANY(aliases) THEN 1.0 ELSE 0.0 END) AS exact_score
            FROM rag_fund_sector
            WHERE enabled = true
            ORDER BY (
                COALESCE(1 - (embedding <=> ?::vector), 0.0) * 0.7 +
                similarity(name, ?) * 0.3 +
                (CASE WHEN name = ? OR ? = ANY(aliases) THEN 0.5 ELSE 0.0 END)
            ) DESC
            LIMIT ?;
            """;

        String qClean = query != null ? query.trim() : "";
        return jdbcTemplate.query(sql, ps -> {
            ps.setString(1, vecStr);
            ps.setString(2, qClean);
            ps.setString(3, qClean);
            ps.setString(4, qClean);
            ps.setString(5, vecStr);
            ps.setString(6, qClean);
            ps.setString(7, qClean);
            ps.setString(8, qClean);
            ps.setInt(9, limit);
        }, (rs, rowNum) -> {
            RagFundSector sector = sectorRowMapper.mapRow(rs, rowNum);
            double vScore = rs.getDouble("vec_score");
            double tScore = rs.getDouble("txt_score");
            double eScore = rs.getDouble("exact_score");
            double finalScore = vScore * 0.7 + tScore * 0.3 + eScore * 0.5;
            return new SchemaRecallResult.SectorMatch(sector, finalScore, Collections.emptyList());
        });
    }

    @Override
    public Optional<RagFundMetric> findMetricByMnemonic(String mnemonic) {
        if (mnemonic == null || mnemonic.isBlank()) return Optional.empty();
        String sql = "SELECT * FROM rag_fund_metric WHERE mnemonic = ?";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, metricRowMapper, mnemonic));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<RagFundSector> findSectorById(String sectorId) {
        if (sectorId == null || sectorId.isBlank()) return Optional.empty();
        String sql = "SELECT * FROM rag_fund_sector WHERE sector_id = ?";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, sectorRowMapper, sectorId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public long countMetrics() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM rag_fund_metric", Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public long countSectors() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM rag_fund_sector", Long.class);
        return count != null ? count : 0L;
    }
}
