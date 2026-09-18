package com.financial.copilot.agent.tools.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.tools.registry.ToolProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * <h1>Wind FundInfraWeb 基础金融数据网关客户端</h1>
 * <p>
 * 对应 {@code provider: http_invoke} 的工具调用。
 * 遵循万得 FundInfraWeb 规范：
 * <ul>
 *   <li>请求方式：POST multipart/form-data，字段名为 {@code content}，内容为包含 {@code Invokes} 的 JSON 结构</li>
 *   <li>请求头：携带 {@code wind.sessionid} 进行链路会话鉴权</li>
 *   <li>响应解析：检验 {@code State == 0} 并提取 {@code Results[0].Value}</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FundInfraWebClient {

    private final ToolProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 调用远程 FundInfraWeb 并直接获取解包后的业务 Value 对象。
     *
     * @param invokeName 方法名 (如 {@code MFCP.Report16Picker2.GetData})
     * @param params     入参 Map
     * @param sessionId  当前用户会话 ID
     * @return 业务数据对象，若远程未配置或调用失败则返回 null 或回退结果
     */
    public Object invokeValue(String invokeName, Map<String, Object> params, String sessionId) {
        Map<String, Object> result = invokeResult(invokeName, params, sessionId);
        if (result == null) {
            return null;
        }

        Object stateObj = result.get("State");
        int state = stateObj instanceof Number ? ((Number) stateObj).intValue() : -1;
        if (state != 0) {
            Object exception = result.get("Exception");
            log.warn("FundInfraWeb invoke returned non-zero state: state={}, name={}, exception={}",
                    state, invokeName, exception);
            return null;
        }

        return result.get("Value");
    }

    /**
     * 调用远程 FundInfraWeb 获取单项完整结果字典。
     */
    public Map<String, Object> invokeResult(String invokeName, Map<String, Object> params, String sessionId) {
        if (!StringUtils.hasText(invokeName)) {
            throw new IllegalArgumentException("FundInfraWeb invoke name is required");
        }

        String serviceUrl = properties.getInfraWeb().getServiceUrl();
        if (!StringUtils.hasText(serviceUrl)) {
            log.debug("WIND_FUNDINFRAWEB_SERVICE_URL is not configured, skipping remote invoke for {}", invokeName);
            return null;
        }

        String effectiveSessionId = StringUtils.hasText(sessionId) ? sessionId : "DEFAULT_SESSION";
        Map<String, Object> invokeItem = Map.of(
                "Name", invokeName,
                "Parameters", params != null ? params : Map.of()
        );
        Map<String, Object> payload = Map.of("Invokes", List.of(invokeItem));

        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("content", jsonPayload);

            String responseText = webClientBuilder.build()
                    .post()
                    .uri(serviceUrl)
                    .header("wind.sessionid", effectiveSessionId)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(properties.getInfraWeb().getTimeoutSeconds()));

            if (!StringUtils.hasText(responseText)) {
                return null;
            }

            Map<String, Object> data = objectMapper.readValue(responseText, new TypeReference<>() {});
            Object resultsObj = data.get("Results");
            if (resultsObj instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> firstResult = (Map<String, Object>) map;
                return firstResult;
            }
        } catch (Exception e) {
            log.warn("FundInfraWeb request failed for name={}: {}", invokeName, e.getMessage());
        }

        return null;
    }
}
