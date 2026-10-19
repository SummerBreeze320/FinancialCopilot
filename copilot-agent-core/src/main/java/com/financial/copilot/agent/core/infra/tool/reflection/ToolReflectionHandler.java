package com.financial.copilot.agent.core.infra.tool.reflection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <h1>工具自省反思循环与熔断处理器 (ToolReflectionHandler)</h1>
 * <p>捕获工具执行层参数与运行时异常，构建同源约束结构化 Observation 供 Agent 纠偏；超过最大重试阈值时触发熔断降级。</p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class ToolReflectionHandler {

    private static final int MAX_RETRY_LIMIT = 2;

    private final Map<String, AtomicInteger> retryTrackers = new ConcurrentHashMap<>();

    /**
     * 处理工具调用异常并生成自省反馈
     *
     * @param stepId 步骤或节点调用标识
     * @param toolName 工具名称
     * @param arguments 调用入参
     * @param error 异常对象
     * @return 结构化自省结果
     */
    public ToolReflectionResult handleError(String stepId,
                                            String toolName,
                                            Map<String, Object> arguments,
                                            Throwable error) {
        AtomicInteger tracker = retryTrackers.computeIfAbsent(stepId, k -> new AtomicInteger(0));
        int count = tracker.incrementAndGet();

        log.warn("Handling tool error for step {}, tool {}: retryCount={}, error={}",
                stepId, toolName, count, error.getMessage());

        if (count <= MAX_RETRY_LIMIT) {
            String observation = String.format(
                    "[工具调用错误自省] 工具 [%s] 执行失败: %s。请根据上述提示和同源约束修正入参后重新尝试调用。传入参数镜像: %s",
                    toolName, error.getMessage(), arguments != null ? arguments : "{}");
            return new ToolReflectionResult(true, count, observation, null);
        } else {
            String observation = String.format(
                    "[工具调用错误自省] 工具 [%s] 累计失败已达最大重试上限 (%d次)，已触发熔断保护，请勿继续重试该工具。",
                    toolName, MAX_RETRY_LIMIT);
            String fallback = String.format(
                    "工具 [%s] 调用超限熔断，已安全降级返回。", toolName);
            return new ToolReflectionResult(false, count, observation, fallback);
        }
    }

    /**
     * 清理指定步骤的重试计数器
     */
    public void reset(String stepId) {
        retryTrackers.remove(stepId);
    }
}
