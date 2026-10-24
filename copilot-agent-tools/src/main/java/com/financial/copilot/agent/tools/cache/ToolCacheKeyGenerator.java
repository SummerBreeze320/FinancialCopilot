package com.financial.copilot.agent.tools.cache;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * <h1>工具缓存规范化键生成器 (Tool Cache Key Generator)</h1>
 * <p>
 * 将工具名称与入参列表序列化为具备强一致性的哈希缓存键。
 * 针对复杂对象、DTO、Map 自动按字段字典序重排，规避因 JSON 字段排列顺序不同导致缓存未命中的问题。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class ToolCacheKeyGenerator {

    private static final String CACHE_KEY_PREFIX = "tool:cache:";
    private final ObjectMapper canonicalMapper;

    public ToolCacheKeyGenerator() {
        this.canonicalMapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * 生成确定性的规范化缓存键
     *
     * @param toolName 工具名称
     * @param args     工具入参数组
     * @return 格式为 "tool:cache:{toolName}:{argsSha256Hex}"
     */
    public String generateKey(String toolName, Object[] args) {
        String cleanToolName = (toolName != null && !toolName.isBlank()) ? toolName.trim() : "anonymous_tool";
        if (args == null || args.length == 0) {
            return CACHE_KEY_PREFIX + cleanToolName + ":_empty_args";
        }

        try {
            // 单一简单类型入参优化（如 String 基金代码，规避开销）
            if (args.length == 1 && args[0] != null) {
                Object singleArg = args[0];
                if (singleArg instanceof String str) {
                    return CACHE_KEY_PREFIX + cleanToolName + ":" + sha256Hex(str.trim());
                }
                if (singleArg instanceof Number || singleArg instanceof Boolean) {
                    return CACHE_KEY_PREFIX + cleanToolName + ":" + singleArg;
                }
            }

            // 多入参或复杂 DTO 规范化序列化
            String canonicalJson = canonicalMapper.writeValueAsString(args);
            return CACHE_KEY_PREFIX + cleanToolName + ":" + sha256Hex(canonicalJson);
        } catch (JsonProcessingException e) {
            log.warn("[TOOL-CACHE-KEY] 规范化参数 JSON 序列化失败，降级为 hashCode: error={}", e.getMessage());
            int hash = 17;
            for (Object arg : args) {
                hash = 31 * hash + (arg != null ? arg.hashCode() : 0);
            }
            return CACHE_KEY_PREFIX + cleanToolName + ":fallback_" + Integer.toHexString(hash);
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // 理论上永远不会发生
            return Integer.toHexString(input.hashCode());
        }
    }
}
