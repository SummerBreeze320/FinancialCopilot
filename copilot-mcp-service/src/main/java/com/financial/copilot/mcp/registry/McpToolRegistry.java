package com.financial.copilot.mcp.registry;

import com.financial.copilot.mcp.protocol.McpToolCallResult;
import com.financial.copilot.mcp.protocol.McpToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>全局 MCP 工具注册中心</h1>
 * <p>
 * 统一管理内置金融工具与 YAML 动态外挂工具，支持热注册与并发查询。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class McpToolRegistry {

    private final Map<String, McpToolHandler> handlers = new ConcurrentHashMap<>();

    /**
     * 注册单个工具处理器
     *
     * @param handler 工具处理器
     */
    public void register(McpToolHandler handler) {
        if (handler != null && handler.getDefinition() != null && handler.getDefinition().getName() != null) {
            String name = handler.getDefinition().getName();
            handlers.put(name, handler);
            log.info("[MCP REGISTRY] 成功注册工具: name={}, desc={}", name, handler.getDefinition().getDescription());
        }
    }

    /**
     * 批量注册工具处理器
     *
     * @param toolHandlers 工具列表
     */
    public void registerAll(Collection<McpToolHandler> toolHandlers) {
        if (toolHandlers != null) {
            toolHandlers.forEach(this::register);
        }
    }

    /**
     * 注销指定名称的工具
     *
     * @param toolName 工具名称
     */
    public void unregister(String toolName) {
        if (toolName != null) {
            handlers.remove(toolName);
            log.info("[MCP REGISTRY] 注销工具: name={}", toolName);
        }
    }

    /**
     * 获取所有可用工具的 MCP 规范定义清单
     *
     * @return 工具定义列表
     */
    public List<McpToolDefinition> listTools() {
        List<McpToolDefinition> list = new ArrayList<>();
        handlers.values().forEach(h -> list.add(h.getDefinition()));
        return list;
    }

    /**
     * 根据工具名执行调用
     *
     * @param toolName  工具标识
     * @param arguments 调用实参字典
     * @return 响应式执行结果 Mono
     */
    public Mono<McpToolCallResult> execute(String toolName, Map<String, Object> arguments) {
        if (toolName == null || toolName.isBlank()) {
            return Mono.just(McpToolCallResult.error("工具名称不能为空"));
        }

        McpToolHandler handler = handlers.get(toolName);
        if (handler == null) {
            log.warn("[MCP REGISTRY] 未找到匹配的工具: name={}", toolName);
            return Mono.just(McpToolCallResult.error("未找到对应工具: " + toolName));
        }

        try {
            return handler.execute(arguments != null ? arguments : Map.of())
                    .onErrorResume(ex -> {
                        log.error("[MCP REGISTRY] 工具执行抛出异常: name={}, err={}", toolName, ex.getMessage(), ex);
                        return Mono.just(McpToolCallResult.error("工具执行异常: " + ex.getMessage()));
                    });
        } catch (Exception ex) {
            log.error("[MCP REGISTRY] 工具执行前置异常: name={}, err={}", toolName, ex.getMessage(), ex);
            return Mono.just(McpToolCallResult.error("工具前置异常: " + ex.getMessage()));
        }
    }

    /**
     * 判断是否已注册该工具
     */
    public boolean containsTool(String toolName) {
        return toolName != null && handlers.containsKey(toolName);
    }

    /**
     * 当前已注册工具数量
     */
    public int count() {
        return handlers.size();
    }
}
