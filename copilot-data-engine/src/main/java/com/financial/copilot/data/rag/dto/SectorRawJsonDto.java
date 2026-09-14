package com.financial.copilot.data.rag.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SectorRawJsonDto {

    @JsonProperty("source_sector_id")
    private String sourceSectorId;

    @JsonProperty("parent_id")
    private String parentId;

    private String name;

    @JsonProperty("name_en")
    private String nameEn;

    private List<String> aliases;

    private String description;

    @JsonProperty("embedding_text")
    private String embeddingText;

    @JsonProperty("is_leaf")
    private Boolean isLeaf;

    @JsonProperty("element_type")
    private Integer elementType;

    private Boolean enabled;
}
