package com.financial.copilot.data.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.rag.po.RagFundMetricPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <h1>基金指标元数据数据访问接口 (MyBatis-Plus Mapper)</h1>
 * <p>
 * 继承 {@link BaseMapper}，提供基础单表 CRUD 能力；
 * 同时基于 PostgreSQL 原生 pgvector 插件与 pg_trgm 扩展提供高效的三路混合召回（向量余弦 + Trigram 相似度 + 精确匹配）。
 * </p>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface RagFundMetricMapper extends BaseMapper<RagFundMetricPO> {

    /**
     * 单条/冲突更新指标元数据与 1024 维向量
     *
     * @param po 指标持久化对象
     * @return 影响行数
     */
    @Insert("""
        INSERT INTO rag_fund_metric (
            mnemonic, index_name, parent_name, description, embedding_text,
            embedding, source_indicator_id, supported_usage, applicable_products, aliases, version, enabled
        ) VALUES (
            #{mnemonic}, #{indexName}, #{parentName}, #{description}, #{embeddingText},
            #{embedding}::vector, #{sourceIndicatorId}, #{supportedUsage, typeHandler=com.financial.copilot.data.config.StringListTypeHandler},
            #{applicableProducts}, #{aliases, typeHandler=com.financial.copilot.data.config.StringListTypeHandler}, #{version}, #{enabled}
        )
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
            enabled = EXCLUDED.enabled
    """)
    int upsertMetric(RagFundMetricPO po);

    /**
     * 三路混合召回基金指标：
     * 1. 向量相似度 (HNSW Cosine): 权重 0.7
     * 2. pg_trgm 词面相似度: 权重 0.3
     * 3. 精确代码/别名匹配: 权重 0.5 叠加
     *
     * @param vecStr 1024 维查询向量字符串（如 "[0.012,-0.045,...]"）
     * @param query  用户查询文本
     * @param topK   最多召回条数
     * @return 包含指标详细字段、vec_score、txt_score、exact_score 及综合总分的键值映射列表
     */
    @Select("""
        SELECT mnemonic, index_name, parent_name, description, embedding_text,
               source_indicator_id, supported_usage, applicable_products, aliases, version, enabled, created_at,
               COALESCE(1 - (embedding <=> #{vecStr}::vector), 0.0) AS vec_score,
               similarity(index_name, #{query}) AS txt_score,
               (CASE WHEN mnemonic ILIKE #{query} OR index_name = #{query} OR #{query} = ANY(aliases) THEN 1.0 ELSE 0.0 END) AS exact_score,
               (COALESCE(1 - (embedding <=> #{vecStr}::vector), 0.0) * 0.7 +
                similarity(index_name, #{query}) * 0.3 +
                (CASE WHEN mnemonic ILIKE #{query} OR index_name = #{query} OR #{query} = ANY(aliases) THEN 0.5 ELSE 0.0 END)
               ) AS total_score
        FROM rag_fund_metric
        WHERE enabled = true
        ORDER BY total_score DESC
        LIMIT #{topK}
    """)
    List<Map<String, Object>> searchHybridMetrics(
            @Param("vecStr") String vecStr,
            @Param("query") String query,
            @Param("topK") int topK
    );
}
