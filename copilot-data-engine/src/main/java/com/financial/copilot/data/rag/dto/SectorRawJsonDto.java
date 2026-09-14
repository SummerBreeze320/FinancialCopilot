package com.financial.copilot.data.rag.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * <h1>基金板块原始 JSON 反序列化传输对象 (DTO)</h1>
 * <p>
 * 对应公募基金板块多层级分类树的标准输入结构。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SectorRawJsonDto {

    /**
     * 原始板块唯一数字代码（16 位字符串）
     */
    @JsonProperty("source_sector_id")
    private String sourceSectorId;

    /**
     * 父级板块代码（根节点为空或 0）
     */
    @JsonProperty("parent_id")
    private String parentId;

    /**
     * 板块中文名称（如 股票型基金、医药生物、中国上市ETF）
     */
    private String name;

    /**
     * 板块英文名称
     */
    @JsonProperty("name_en")
    private String nameEn;

    /**
     * 板块别名列表
     */
    private List<String> aliases;

    /**
     * 板块描述信息
     */
    private String description;

    /**
     * 原始 Embedding 文本（若为空由解析器依据层级路径构建）
     */
    @JsonProperty("embedding_text")
    private String embeddingText;

    /**
     * 是否底层叶子节点（true 表示叶子）
     */
    @JsonProperty("is_leaf")
    private Boolean isLeaf;

    /**
     * 元素分类枚举类型
     */
    @JsonProperty("element_type")
    private Integer elementType;

    /**
     * 是否启用
     */
    private Boolean enabled;
}
