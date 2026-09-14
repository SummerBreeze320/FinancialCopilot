package com.financial.copilot.data.rag.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricRawJsonDto {

    private String mnemonic;

    @JsonProperty("index_name")
    @JsonAlias({"name"})
    private String indexName;

    @JsonProperty("parent_name")
    private String parentName;

    private String description;

    @JsonProperty("embedding_text")
    private String embeddingText;

    private Integer version;

    private Boolean enabled;

    @JsonProperty("source_indicator_id")
    private Long sourceIndicatorId;

    @JsonProperty("supported_usage")
    private List<String> supportedUsage;

    @JsonProperty("applicable_products")
    private String applicableProducts;

    private List<String> aliases;
}
