package com.financial.copilot.data.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.rag.po.RagFundSectorPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <h1>基金板块多层级分类数据访问接口 (MyBatis-Plus Mapper)</h1>
 * <p>
 * 继承 {@link BaseMapper}，提供基础单表 CRUD 能力；
 * 基于 PostgreSQL 原生 pgvector 插件与 pg_trgm 扩展提供高效的三路混合召回（向量余弦 + Trigram 相似度 + 精确匹配）。
 * </p>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface RagFundSectorMapper extends BaseMapper<RagFundSectorPO> {

    /**
     * 单条/冲突更新板块元数据与 1024 维向量
     *
     * @param po 板块持久化对象
     * @return 影响行数
     */
    @Insert("""
        INSERT INTO rag_fund_sector (
            sector_id, parent_id, name, name_en, aliases, description,
            embedding_text, embedding, is_leaf, element_type, tree_level, full_path_names, enabled
        ) VALUES (
            #{sectorId}, #{parentId}, #{name}, #{nameEn}, #{aliases, typeHandler=com.financial.copilot.data.config.StringListTypeHandler},
            #{description}, #{embeddingText}, #{embedding}::vector, #{isLeaf}, #{elementType}, #{treeLevel}, #{fullPathNames}, #{enabled}
        )
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
            enabled = EXCLUDED.enabled
    """)
    int upsertSector(RagFundSectorPO po);

    /**
     * 三路混合召回基金板块：
     * 1. 向量相似度 (HNSW Cosine): 权重 0.7
     * 2. pg_trgm 词面相似度: 权重 0.3
     * 3. 精确名称/别名匹配: 权重 0.5 叠加
     *
     * @param vecStr 1024 维查询向量字符串（如 "[0.012,-0.045,...]"）
     * @param query  用户查询文本
     * @param topK   最多召回条数
     * @return 包含板块详细字段、vec_score、txt_score、exact_score 及综合总分的键值映射列表
     */
    @Select("""
        SELECT sector_id, parent_id, name, name_en, aliases, description,
               embedding_text, is_leaf, element_type, tree_level, full_path_names, enabled, created_at,
               COALESCE(1 - (embedding <=> #{vecStr}::vector), 0.0) AS vec_score,
               similarity(name, #{query}) AS txt_score,
               (CASE WHEN name = #{query} OR #{query} = ANY(aliases) THEN 1.0 ELSE 0.0 END) AS exact_score,
               (COALESCE(1 - (embedding <=> #{vecStr}::vector), 0.0) * 0.7 +
                similarity(name, #{query}) * 0.3 +
                (CASE WHEN name = #{query} OR #{query} = ANY(aliases) THEN 0.5 ELSE 0.0 END)
               ) AS total_score
        FROM rag_fund_sector
        WHERE enabled = true
        ORDER BY total_score DESC
        LIMIT #{topK}
    """)
    List<Map<String, Object>> searchHybridSectors(
            @Param("vecStr") String vecStr,
            @Param("query") String query,
            @Param("topK") int topK
    );
}
