package com.financial.copilot.agent.core.memory.remote.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 远程记忆检索请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecallRequestDTO {

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("query_text")
    private String queryText;

    @JsonProperty("task_type")
    private String taskType;

    @JsonProperty("token_budget")
    private Integer tokenBudget;

    @JsonProperty("context")
    private Map<String, Object> context;
}
