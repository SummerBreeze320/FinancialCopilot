package com.financial.copilot.data.rag.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * <h1>基金指标原始 JSON 反序列化传输对象 (DTO)</h1>
 * <p>
 * 对应 <code>docs/temp/metrics.json</code> 文件中的指标元数据结构。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricRawJsonDto {

    /**
     * 指标唯一助记符代码（如 f_return_1y, f_risk_sharpe）
     */
    private String mnemonic;

    /**
     * 指标中文标准名称（如 近1年回报）
     */
    @JsonProperty("index_name")
    @JsonAlias({"name"})
    private String indexName;

    /**
     * 指标一级业务大类名称（如 收益指标、风险指标）
     */
    @JsonProperty("parent_name")
    private String parentName;

    /**
     * 指标详细中文描述与释义
     */
    private String description;

    /**
     * 原始 Embedding 文本（若为空则在解析器中自动拼接构建）
     */
    @JsonProperty("embedding_text")
    private String embeddingText;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 来源指标 ID
     */
    @JsonProperty("source_indicator_id")
    private Long sourceIndicatorId;

    /**
     * 支持的下游场景类型（如 filter、sort、group）
     */
    @JsonProperty("supported_usage")
    private List<String> supportedUsage;

    /**
     * 适用基金产品范围
     */
    @JsonProperty("applicable_products")
    private String applicableProducts;

    /**
     * 指标别名列表
     */
    private List<String> aliases;
}
