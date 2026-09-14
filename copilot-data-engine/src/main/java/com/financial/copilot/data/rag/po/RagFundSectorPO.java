package com.financial.copilot.data.rag.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financial.copilot.data.config.StringListTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <h1>公募基金板块分类持久化对象 (PO)</h1>
 * <p>
 * 对应 PostgreSQL 数据库表 <code>rag_fund_sector</code>。
 * 存储 1,712 项公募基金行业、主题、风格分类的多层级树拓扑、层级面包屑及 1024 维 HNSW 余弦向量。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "rag_fund_sector", autoResultMap = true)
public class RagFundSectorPO {

    /**
     * 板块唯一数字编码（主键，16 位数字字符串）
     */
    @TableId(value = "sector_id", type = IdType.INPUT)
    private String sectorId;

    /**
     * 父级板块编码（根节点“内地公募基金”父级为空或 0）
     */
    @TableField("parent_id")
    private String parentId;

    /**
     * 板块标准中文名称（如 股票型基金、医药生物、中国上市ETF）
     */
    @TableField("name")
    private String name;

    /**
     * 板块对应英文名称
     */
    @TableField("name_en")
    private String nameEn;

    /**
     * 板块别名列表（用于精准匹配用户简称）
     */
    @TableField(value = "aliases", typeHandler = StringListTypeHandler.class)
    private List<String> aliases;

    /**
     * 板块说明信息
     */
    @TableField("description")
    private String description;

    /**
     * 预处理拼接后的 Embedding 原始文本（含层级面包屑全路径与板块名）
     */
    @TableField("embedding_text")
    private String embeddingText;

    /**
     * 1024 维高维向量字符串（pgvector 格式：[0.0123, -0.0456, ...]）
     */
    @TableField("embedding")
    private String embedding;

    /**
     * 是否底层叶子节点（true 表示无子板块，直接归属具体基金产品）
     */
    @TableField("is_leaf")
    private Boolean isLeaf;

    /**
     * 元素分类枚举值（通常公募板块为 6）
     */
    @TableField("element_type")
    private Integer elementType;

    /**
     * 树层级深度（根节点为 0，逐级递增）
     */
    @TableField("tree_level")
    private Integer treeLevel;

    /**
     * 全路径层级面包屑（如 内地公募基金 > 封闭式基金 > 契约型封闭式基金）
     */
    @TableField("full_path_names")
    private String fullPathNames;

    /**
     * 是否启用状态
     */
    @TableField("enabled")
    private Boolean enabled;

    /**
     * 创建与入库时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
