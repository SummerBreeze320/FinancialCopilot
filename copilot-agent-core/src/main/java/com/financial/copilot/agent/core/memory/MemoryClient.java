package com.financial.copilot.agent.core.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

/**
 * HTTP 适配层 — 调用 Python 记忆服务 (FastAPI + Graphiti)。
 *
 * 替换原有 ShortTermMemoryService + LongTermMemoryService + MemoryRefinementTask。
 * 短期/长期/画像统一由 Python 侧管理，Java 端只做 HTTP 调用。
 */
@Component
public class MemoryClient {

    private static final Logger log = LoggerFactory.getLogger(MemoryClient.class);

    private final WebClient webClient;
    private final Duration timeout = Duration.ofSeconds(10);

    public MemoryClient(@Value("${copilot.memory-service.url:http://localhost:8700}") String baseUrl) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    // ── 短期记忆 ──────────────────────────────────────────────

    public void addMessage(String sessionId, String userId, String role, String content) {
        try {
            webClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/v1/sessions/{sid}/messages")
                            .queryParam("user_id", userId)
                            .build(sessionId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("role", role, "content", content))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .block();
        } catch (Exception e) {
            log.warn("addMessage failed (non-blocking): {}", e.getMessage());
        }
    }

    public List<String> getContext(String sessionId) {
        try {
            ContextResponse resp = webClient.get()
                    .uri("/v1/sessions/{sid}/context", sessionId)
                    .retrieve()
                    .bodyToMono(ContextResponse.class)
                    .timeout(timeout)
                    .block();
            if (resp == null || resp.messages == null) return List.of();
            List<String> result = new ArrayList<>();
            for (Map<String, Object> msg : resp.messages) {
                String role = String.valueOf(msg.getOrDefault("role", "")).toUpperCase();
                String text = String.valueOf(msg.getOrDefault("content", ""));
                result.add(role + ": " + text);
            }
            return result;
        } catch (Exception e) {
            log.warn("getContext failed: {}", e.getMessage());
            return List.of();
        }
    }

    public void clearSession(String sessionId) {
        try {
            webClient.delete()
                    .uri("/v1/sessions/{sid}", sessionId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .block();
        } catch (Exception e) {
            log.warn("clearSession failed: {}", e.getMessage());
        }
    }

    // ── 长期记忆 ──────────────────────────────────────────────

    public List<String> searchMemory(String query, int maxResults) {
        try {
            MemorySearchResponse resp = webClient.post()
                    .uri("/v1/memory/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("query", query, "max_results", maxResults))
                    .retrieve()
                    .bodyToMono(MemorySearchResponse.class)
                    .timeout(timeout)
                    .block();
            if (resp == null || resp.results == null) return List.of();
            List<String> facts = new ArrayList<>();
            for (FactItem f : resp.results) {
                facts.add(f.fact);
            }
            return facts;
        } catch (Exception e) {
            log.warn("searchMemory failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── 用户画像 ──────────────────────────────────────────────

    public UserProfileResponse getProfile(String userId) {
        try {
            return webClient.get()
                    .uri("/v1/users/{uid}/profile", userId)
                    .retrieve()
                    .bodyToMono(UserProfileResponse.class)
                    .timeout(timeout)
                    .block();
        } catch (Exception e) {
            log.warn("getProfile failed: {}", e.getMessage());
            return null;
        }
    }

    public String getProfilePrompt(String userId) {
        UserProfileResponse profile = getProfile(userId);
        if (profile == null || profile.dimensions == null || profile.dimensions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("用户画像:\n");
        for (Map.Entry<String, ProfileEntry> e : profile.dimensions.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ").append(e.getValue().value).append("\n");
        }
        return sb.toString();
    }

    public void extractProfile(String userId, String dialogue) {
        try {
            webClient.post()
                    .uri("/v1/users/{uid}/profile/extract", userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("dialogue", dialogue))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (Exception e) {
            log.warn("extractProfile failed: {}", e.getMessage());
        }
    }

    // ── DTO ───────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContextResponse {
        public String session_id;
        public List<Map<String, Object>> messages;
        public int total;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MemorySearchResponse {
        public List<FactItem> results;
        public int total;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FactItem {
        public String uuid;
        public String fact;
        public String valid_at;
        public String invalid_at;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserProfileResponse {
        public String user_id;
        public Map<String, ProfileEntry> dimensions;
        public String last_active;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProfileEntry {
        public String dimension;
        public String value;
        public double confidence;
        public String updated_at;
    }
}
