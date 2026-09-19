package com.financial.copilot.agent.core.memory.remote.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 会话离线加工请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessSessionRequestDTO {

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("session_data")
    private Map<String, Object> sessionData;
}
