package com.financial.copilot.agent.core.infra.agentscope.loop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * <h1>工具调用参数指纹 (ToolCallFingerprint)</h1>
 * <p>基于工具名称与递归排序规范化的参数 JSON 构造调用特征签名，用于跨轮次的工具调用去重与死循环检测。</p>
 *
 * @author FinancialCopilot
 */
@Getter
public final class ToolCallFingerprint {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String toolName;
    private final String canonicalArguments;

    private ToolCallFingerprint(String toolName, String canonicalArguments) {
        this.toolName = Objects.requireNonNull(toolName, "toolName must not be null");
        this.canonicalArguments = canonicalArguments != null ? canonicalArguments : "{}";
    }

    /**
     * 构建工具调用的规范化参数指纹
     *
     * @param toolName 工具名称
     * @param rawArguments 原始入参 Map
     * @return 具备唯一特征签名的指纹对象
     */
    public static ToolCallFingerprint of(String toolName, Map<String, Object> rawArguments) {
        String canonicalJson = canonicalize(rawArguments);
        return new ToolCallFingerprint(toolName, canonicalJson);
    }

    private static String canonicalize(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            Map<String, Object> sortedMap = sortDeep(arguments);
            return OBJECT_MAPPER.writeValueAsString(sortedMap);
        } catch (JsonProcessingException e) {
            return arguments.toString();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sortDeep(Map<String, Object> map) {
        Map<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> subMap) {
                sorted.put(entry.getKey(), sortDeep((Map<String, Object>) subMap));
            } else {
                sorted.put(entry.getKey(), value);
            }
        }
        return sorted;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ToolCallFingerprint that = (ToolCallFingerprint) o;
        return Objects.equals(toolName, that.toolName) &&
                Objects.equals(canonicalArguments, that.canonicalArguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(toolName, canonicalArguments);
    }

    @Override
    public String toString() {
        return toolName + "(" + canonicalArguments + ")";
    }
}
