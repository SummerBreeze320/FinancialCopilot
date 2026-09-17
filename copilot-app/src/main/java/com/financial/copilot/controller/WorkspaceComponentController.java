package com.financial.copilot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.tools.client.ComponentDataClient;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.config.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基金投研工作台组件按需内容读取控制器。
 * 为前端工作台卡片提供按需加载/刷新组件明细数据的 API。
 */
@Slf4j
@RestController
@RequestMapping
@RequiredArgsConstructor
public class WorkspaceComponentController {

    private final ComponentDataClient componentDataClient;
    private final ObjectMapper objectMapper;

    /**
     * 按需获取工作台组件明细内容。
     * 同时支持 /api/v1/research/workspace/components/{refId}/content 与 /api/research/workspace/components/{refId}/content。
     *
     * @param refId     组件引用 ID
     * @param contextId 上下文 ID (可选)
     * @return 统一封装的组件内容
     */
    @GetMapping({
            "/api/v1/research/workspace/components/{refId}/content",
            "/api/research/workspace/components/{refId}/content"
    })
    public Mono<ApiResult<Map<String, Object>>> getComponentContent(
            @PathVariable String refId,
            @RequestParam(required = false) String contextId) {
        return SecurityUtils.requireCurrentUserId(null)
                .flatMap(userId -> Mono.fromCallable(() -> {
                    String raw = componentDataClient.read(contextId, refId);
                    Object content = parseContent(raw);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("refId", refId);
                    data.put("contextId", contextId != null ? contextId : "");
                    data.put("content", content);
                    return ApiResult.success(data);
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    private Object parseContent(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (Exception e) {
            log.debug("Component content is not JSON format, return raw string: {}", e.getMessage());
            return raw;
        }
    }
}
