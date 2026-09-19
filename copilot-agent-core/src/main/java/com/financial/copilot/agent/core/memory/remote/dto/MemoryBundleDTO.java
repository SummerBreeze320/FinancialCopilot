package com.financial.copilot.agent.core.memory.remote.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 远程记忆检索响应包 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryBundleDTO {

    @JsonProperty("compact_context")
    private String compactContext;

    @JsonProperty("recalled_items")
    private List<RecalledItemDTO> recalledItems;

    @JsonProperty("total_tokens")
    private Integer totalTokens;

    @JsonProperty("token_budget")
    private Integer tokenBudget;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecalledItemDTO {
        @JsonProperty("memory_id")
        private String memoryId;

        @JsonProperty("version")
        private Integer version;

        @JsonProperty("content")
        private String content;

        @JsonProperty("compact_text")
        private String compactText;

        @JsonProperty("token_count")
        private Integer tokenCount;
    }
}
