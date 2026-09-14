package com.financial.copilot.data.rag.adapter;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.financial.copilot.data.rag.mapper.RagFundMetricMapper;
import com.financial.copilot.data.rag.mapper.RagFundSectorMapper;
import com.financial.copilot.data.rag.po.RagFundMetricPO;
import com.financial.copilot.data.rag.po.RagFundSectorPO;
import com.financial.copilot.domain.rag.entity.RagFundMetric;
import com.financial.copilot.domain.rag.entity.RagFundSector;
import com.financial.copilot.domain.rag.entity.SchemaRecallResult;
import com.financial.copilot.domain.rag.port.RagSchemaPort;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * <h1>基于 MyBatis-Plus 与 PostgreSQL 16 + pgvector 的模式检索适配器</h1>
 * <p>
 * 实现领域层 {@link RagSchemaPort} 接口，依托 MyBatis-Plus 机制与 PostgreSQL 原生扩展：
 * <ul>
 *   <li>使用 {@link RagFundMetricMapper} 与 {@link RagFundSectorMapper} 实现强类型实体映射与持久化；</li>
 *   <li>通过 MyBatis 的 {@link ExecutorType#BATCH} 高性能批处理机制执行百万级/千级元数据冲突合并（Upsert）；</li>
 *   <li>基于 pgvector HNSW 余弦向量距离与 pg_trgm 相似度提供高精度的三路混合检索（语义 + 词面 + 精确匹配）。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Repository
public class PostgresRagSchemaAdapter implements RagSchemaPort {

    private final RagFundMetricMapper metricMapper;
    private final RagFundSectorMapper sectorMapper;
    private final SqlSessionFactory sqlSessionFactory;

    /**
     * Spring 容器标准依赖注入构造函数
     *
     * @param metricMapper      指标数据访问 Mapper
     * @param sectorMapper      板块数据访问 Mapper
     * @param sqlSessionFactory MyBatis SqlSessionFactory
     */
    @Autowired
    public PostgresRagSchemaAdapter(
            RagFundMetricMapper metricMapper,
            RagFundSectorMapper sectorMapper,
            SqlSessionFactory sqlSessionFactory
    ) {
        this.metricMapper = metricMapper;
        this.sectorMapper = sectorMapper;
        this.sqlSessionFactory = sqlSessionFactory;
    }

    /**
     * 独立单元测试与非 Spring 容器环境兼容构造函数
     *
     * @param jdbcTemplate Spring JdbcTemplate
     */
    public PostgresRagSchemaAdapter(JdbcTemplate jdbcTemplate) {
        DataSource dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource(), "DataSource 不能为空");
        Environment environment = new Environment("rag-standalone", new JdbcTransactionFactory(), dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration(environment);
        configuration.addMapper(RagFundMetricMapper.class);
        configuration.addMapper(RagFundSectorMapper.class);
        this.sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        SqlSession session = this.sqlSessionFactory.openSession(true);
        this.metricMapper = session.getMapper(RagFundMetricMapper.class);
        this.sectorMapper = session.getMapper(RagFundSectorMapper.class);
    }

    /**
     * 将浮点数组向量格式化为 PostgreSQL pgvector 文本形式："[0.0123,-0.0456,...]"
     *
     * @param vec 浮点向量数组
     * @return 格式化后的 pgvector 字符串，若为空则返回 null
     */
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

    /**
     * 批量持久化/更新公募基金指标元数据及 1024 维向量
     *
     * @param metrics 领域指标实体列表
     */
    @Override
    public void upsertMetrics(List<RagFundMetric> metrics) {
        if (metrics == null || metrics.isEmpty()) return;

        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            RagFundMetricMapper batchMapper = session.getMapper(RagFundMetricMapper.class);
            int batchSize = 50;
            for (int i = 0; i < metrics.size(); i++) {
                RagFundMetric m = metrics.get(i);
                RagFundMetricPO po = toMetricPO(m);
                batchMapper.upsertMetric(po);

                if ((i + 1) % batchSize == 0 || i == metrics.size() - 1) {
                    session.commit();
                    session.clearCache();
                }
            }
        }
        log.info("[RAG-POSTGRES] 基于 MyBatis-Plus 批量 Upsert 指标完成: count={}", metrics.size());
    }

    /**
     * 批量持久化/更新公募基金板块多层级分类及 1024 维向量
     *
     * @param sectors 领域板块分类实体列表
     */
    @Override
    public void upsertSectors(List<RagFundSector> sectors) {
        if (sectors == null || sectors.isEmpty()) return;

        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            RagFundSectorMapper batchMapper = session.getMapper(RagFundSectorMapper.class);
            int batchSize = 100;
            for (int i = 0; i < sectors.size(); i++) {
                RagFundSector s = sectors.get(i);
                RagFundSectorPO po = toSectorPO(s);
                batchMapper.upsertSector(po);

                if ((i + 1) % batchSize == 0 || i == sectors.size() - 1) {
                    session.commit();
                    session.clearCache();
                }
            }
        }
        log.info("[RAG-POSTGRES] 基于 MyBatis-Plus 批量 Upsert 板块完成: count={}", sectors.size());
    }

    /**
     * 执行公募指标三路混合检索（向量余弦 70% + 词面 Trigram 30% + 精确匹配 50% 增益）
     *
     * @param query    用户查询自然语言
     * @param queryVec 用户查询对应的 1024 维 Embedding 向量
     * @param topK     最大召回数
     * @return 召回指标与加权得分列表
     */
    @Override
    public List<SchemaRecallResult.MetricMatch> searchMetrics(String query, float[] queryVec, int topK) {
        String vecStr = formatPgVector(queryVec);
        int limit = Math.max(1, topK);
        String qClean = query != null ? query.trim() : "";

        List<Map<String, Object>> rows = metricMapper.searchHybridMetrics(vecStr, qClean, limit);
        List<SchemaRecallResult.MetricMatch> results = new ArrayList<>(rows.size());

        for (Map<String, Object> row : rows) {
            RagFundMetric metric = mapToMetricDomain(row);
            double totalScore = row.get("total_score") != null ? ((Number) row.get("total_score")).doubleValue() : 0.0;
            results.add(new SchemaRecallResult.MetricMatch(metric, totalScore, metric.getParentName()));
        }

        return results;
    }

    /**
     * 执行公募板块三路混合检索（向量余弦 70% + 词面 Trigram 30% + 精确匹配 50% 增益）
     *
     * @param query    用户查询自然语言
     * @param queryVec 用户查询对应的 1024 维 Embedding 向量
     * @param topK     最大召回数
     * @return 召回板块与加权得分列表
     */
    @Override
    public List<SchemaRecallResult.SectorMatch> searchSectors(String query, float[] queryVec, int topK) {
        String vecStr = formatPgVector(queryVec);
        int limit = Math.max(1, topK);
        String qClean = query != null ? query.trim() : "";

        List<Map<String, Object>> rows = sectorMapper.searchHybridSectors(vecStr, qClean, limit);
        List<SchemaRecallResult.SectorMatch> results = new ArrayList<>(rows.size());

        for (Map<String, Object> row : rows) {
            RagFundSector sector = mapToSectorDomain(row);
            double totalScore = row.get("total_score") != null ? ((Number) row.get("total_score")).doubleValue() : 0.0;
            results.add(new SchemaRecallResult.SectorMatch(sector, totalScore, Collections.emptyList()));
        }

        return results;
    }

    /**
     * 根据指标唯一助记符查询指标明细
     *
     * @param mnemonic 指标助记符（如 f_return_1y）
     * @return 指标实体 Optional
     */
    @Override
    public Optional<RagFundMetric> findMetricByMnemonic(String mnemonic) {
        if (mnemonic == null || mnemonic.isBlank()) return Optional.empty();
        RagFundMetricPO po = metricMapper.selectById(mnemonic);
        return Optional.ofNullable(po).map(this::toMetricDomain);
    }

    /**
     * 根据板块 16 位唯一编码查询板块明细
     *
     * @param sectorId 板块编码
     * @return 板块实体 Optional
     */
    @Override
    public Optional<RagFundSector> findSectorById(String sectorId) {
        if (sectorId == null || sectorId.isBlank()) return Optional.empty();
        RagFundSectorPO po = sectorMapper.selectById(sectorId);
        return Optional.ofNullable(po).map(this::toSectorDomain);
    }

    /**
     * 统计当前数据库中已注册指标总数
     *
     * @return 指标数量
     */
    @Override
    public long countMetrics() {
        Long count = metricMapper.selectCount(null);
        return count != null ? count : 0L;
    }

    /**
     * 统计当前数据库中已注册板块分类总数
     *
     * @return 板块数量
     */
    @Override
    public long countSectors() {
        Long count = sectorMapper.selectCount(null);
        return count != null ? count : 0L;
    }

    // ================== PO 与领域实体转换器 ==================

    /**
     * 领域指标实体转 MyBatis-Plus 持久化对象
     */
    private RagFundMetricPO toMetricPO(RagFundMetric m) {
        return RagFundMetricPO.builder()
                .mnemonic(m.getMnemonic())
                .indexName(m.getIndexName())
                .parentName(m.getParentName())
                .description(m.getDescription())
                .embeddingText(m.getEmbeddingText())
                .embedding(formatPgVector(m.getEmbedding()))
                .sourceIndicatorId(m.getSourceIndicatorId())
                .supportedUsage(m.getSupportedUsage())
                .applicableProducts(m.getApplicableProducts())
                .aliases(m.getAliases())
                .version(m.getVersion() != null ? m.getVersion() : 1)
                .enabled(m.isEnabled())
                .build();
    }

    /**
     * 领域板块实体转 MyBatis-Plus 持久化对象
     */
    private RagFundSectorPO toSectorPO(RagFundSector s) {
        return RagFundSectorPO.builder()
                .sectorId(s.getSectorId())
                .parentId(s.getParentId())
                .name(s.getName())
                .nameEn(s.getNameEn())
                .aliases(s.getAliases())
                .description(s.getDescription())
                .embeddingText(s.getEmbeddingText())
                .embedding(formatPgVector(s.getEmbedding()))
                .isLeaf(s.isLeaf())
                .elementType(s.getElementType() != null ? s.getElementType() : 6)
                .treeLevel(s.getTreeLevel() != null ? s.getTreeLevel() : 0)
                .fullPathNames(s.getFullPathNames())
                .enabled(s.isEnabled())
                .build();
    }

    /**
     * MyBatis-Plus 持久化对象转领域指标实体
     */
    private RagFundMetric toMetricDomain(RagFundMetricPO po) {
        return RagFundMetric.builder()
                .mnemonic(po.getMnemonic())
                .indexName(po.getIndexName())
                .parentName(po.getParentName())
                .description(po.getDescription())
                .embeddingText(po.getEmbeddingText())
                .sourceIndicatorId(po.getSourceIndicatorId())
                .supportedUsage(po.getSupportedUsage())
                .applicableProducts(po.getApplicableProducts())
                .aliases(po.getAliases())
                .version(po.getVersion())
                .enabled(Boolean.TRUE.equals(po.getEnabled()))
                .createdAt(po.getCreatedAt())
                .build();
    }

    /**
     * MyBatis-Plus 持久化对象转领域板块实体
     */
    private RagFundSector toSectorDomain(RagFundSectorPO po) {
        return RagFundSector.builder()
                .sectorId(po.getSectorId())
                .parentId(po.getParentId())
                .name(po.getName())
                .nameEn(po.getNameEn())
                .aliases(po.getAliases())
                .description(po.getDescription())
                .embeddingText(po.getEmbeddingText())
                .isLeaf(Boolean.TRUE.equals(po.getIsLeaf()))
                .elementType(po.getElementType())
                .treeLevel(po.getTreeLevel())
                .fullPathNames(po.getFullPathNames())
                .enabled(Boolean.TRUE.equals(po.getEnabled()))
                .createdAt(po.getCreatedAt())
                .build();
    }

    /**
     * 混合检索 Map 结果转换为领域指标实体
     */
    private RagFundMetric mapToMetricDomain(Map<String, Object> map) {
        List<String> usageList = parseArrayField(map.get("supported_usage"));
        List<String> aliasList = parseArrayField(map.get("aliases"));
        Object tsObj = map.get("created_at");
        LocalDateTime createdAt = null;
        if (tsObj instanceof java.sql.Timestamp ts) {
            createdAt = ts.toLocalDateTime();
        } else if (tsObj instanceof LocalDateTime ldt) {
            createdAt = ldt;
        }

        return RagFundMetric.builder()
                .mnemonic((String) map.get("mnemonic"))
                .indexName((String) map.get("index_name"))
                .parentName((String) map.get("parent_name"))
                .description((String) map.get("description"))
                .embeddingText((String) map.get("embedding_text"))
                .sourceIndicatorId(map.get("source_indicator_id") != null ? ((Number) map.get("source_indicator_id")).longValue() : null)
                .supportedUsage(usageList)
                .applicableProducts((String) map.get("applicable_products"))
                .aliases(aliasList)
                .version(map.get("version") != null ? ((Number) map.get("version")).intValue() : 1)
                .enabled(Boolean.TRUE.equals(map.get("enabled")))
                .createdAt(createdAt)
                .build();
    }

    /**
     * 混合检索 Map 结果转换为领域板块实体
     */
    private RagFundSector mapToSectorDomain(Map<String, Object> map) {
        List<String> aliasList = parseArrayField(map.get("aliases"));
        Object tsObj = map.get("created_at");
        LocalDateTime createdAt = null;
        if (tsObj instanceof java.sql.Timestamp ts) {
            createdAt = ts.toLocalDateTime();
        } else if (tsObj instanceof LocalDateTime ldt) {
            createdAt = ldt;
        }

        return RagFundSector.builder()
                .sectorId((String) map.get("sector_id"))
                .parentId((String) map.get("parent_id"))
                .name((String) map.get("name"))
                .nameEn((String) map.get("name_en"))
                .aliases(aliasList)
                .description((String) map.get("description"))
                .embeddingText((String) map.get("embedding_text"))
                .isLeaf(Boolean.TRUE.equals(map.get("is_leaf")))
                .elementType(map.get("element_type") != null ? ((Number) map.get("element_type")).intValue() : 6)
                .treeLevel(map.get("tree_level") != null ? ((Number) map.get("tree_level")).intValue() : 0)
                .fullPathNames((String) map.get("full_path_names"))
                .enabled(Boolean.TRUE.equals(map.get("enabled")))
                .createdAt(createdAt)
                .build();
    }

    /**
     * 解析数据库返回的数组对象为 List&lt;String&gt;
     */
    @SuppressWarnings("unchecked")
    private List<String> parseArrayField(Object obj) {
        if (obj == null) return Collections.emptyList();
        if (obj instanceof String[] arr) return Arrays.asList(arr);
        if (obj instanceof List) return (List<String>) obj;
        if (obj instanceof java.sql.Array sqlArr) {
            try {
                Object inner = sqlArr.getArray();
                if (inner instanceof String[] arr) return Arrays.asList(arr);
            } catch (SQLException ignored) {
            }
        }
        return Collections.emptyList();
    }
}
