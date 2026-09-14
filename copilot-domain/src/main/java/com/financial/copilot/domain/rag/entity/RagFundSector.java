package com.financial.copilot.domain.rag.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <h1>基金板块与分类层级树 RAG 领域实体 (对应 sectors.json)</h1>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagFundSector {

    /** 板块唯一编码 (主键，如: 1000009160000000) */
    private String sectorId;

    /** 上级板块编码 (根节点为 null) */
    private String parentId;

    /** 板块中文名称 */
    private String name;

    /** 英文名称 (可选) */
    private String nameEn;

    /** 别名/缩写/同义词数组，如: ["ETF", "场内基金"] */
    private List<String> aliases;

    /** 板块业务释义 */
    private String description;

    /** 向量化语料文本 */
    private String embeddingText;

    /** 1024 维向量 */
    private float[] embedding;

    /** 是否为叶子分类节点 */
    @Builder.Default
    private boolean isLeaf = true;

    /** 分类元素类型 */
    @Builder.Default
    private Integer elementType = 6;

    /** 树深度 (根节点为 0, 子节点递增) */
    @Builder.Default
    private Integer treeLevel = 0;

    /** 完整树分类层级链 (如: 内地公募基金 > 中国上市ETF) */
    private String fullPathNames;

    /** 是否启用 */
    @Builder.Default
    private boolean enabled = true;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
