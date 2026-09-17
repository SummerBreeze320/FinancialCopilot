package com.financial.copilot.agent.tools.workspace;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 稳定且带入参维度的组件实例唯一 ID 工厂。
 * 格式例如: FundInfoForDefault:005827.OF+163402.OF:2026-09-15
 */
@Component
public class ComponentInstanceIdFactory {

    public String create(String componentId, Map<String, Object> params) {
        String base = clean(componentId);
        if (params == null || params.isEmpty()) {
            return base;
        }
        String codes = compact(params.get("windCodes"));
        if (codes.isBlank()) {
            codes = compact(params.get("fundCodes"));
        }
        String date = firstNonBlank(
                compact(params.get("endDate")),
                compact(params.get("reportDate")),
                compact(params.get("startDate"))
        );
        return joinNonBlank(base, codes, date);
    }

    private String compact(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(this::clean)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.joining("+"));
        }
        return clean(String.valueOf(value));
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().replace(":", "_").replace("/", "_");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String joinNonBlank(String... values) {
        return Arrays.stream(values)
                .filter(v -> v != null && !v.isBlank())
                .collect(Collectors.joining(":"));
    }
}
