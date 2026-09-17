package com.financial.copilot.agent.tools.registry;

import com.financial.copilot.agent.tools.model.ToolDefinition;
import com.financial.copilot.agent.tools.model.UITreeComponent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一 Tool 注册表 (ToolRegistry)。
 * 维护系统已加载的所有模块化 Tool 及其绑定的一组组件。
 * 提供按「单基金深度分析 (SINGLE_FUND_ANALYSIS)」与「多基金横向对比 (MULTI_FUND_COMPARISON)」
 * 两大场景维度的精确检索能力。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final ToolDefinitionLoader loader;
    private final Map<String, ToolDefinition> registry = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * 刷新并重载所有模块化 Tool 定义。
     */
    public synchronized void refresh() {
        registry.clear();
        List<ToolDefinition> list = loader.loadDefinitions();
        for (ToolDefinition def : list) {
            registry.put(def.getId(), def);
        }
        log.info("ToolRegistry initialized with {} modular tools: {} Analysis Tools, {} Comparison Tools",
                registry.size(), getAnalysisTools().size(), getComparisonTools().size());
    }

    /**
     * 注册单个 Tool。
     */
    public void register(ToolDefinition def) {
        if (def != null && def.getId() != null) {
            registry.put(def.getId(), def);
        }
    }

    /**
     * 根据 Tool ID 获取 Tool 定义。
     */
    public Optional<ToolDefinition> get(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(registry.get(id));
    }

    /**
     * 获取所有已注册 Tool。
     */
    public Collection<ToolDefinition> getAll() {
        return Collections.unmodifiableCollection(registry.values());
    }

    /**
     * 获取所有【深度分析与画像 (ANALYSIS)】类工具。
     */
    public List<ToolDefinition> getAnalysisTools() {
        return registry.values().stream()
                .filter(t -> "ANALYSIS".equalsIgnoreCase(t.getScope()) || "SINGLE_FUND_ANALYSIS".equalsIgnoreCase(t.getScope()))
                .sorted(Comparator.comparing(t -> t.getSort() == null ? 99 : t.getSort()))
                .toList();
    }

    /**
     * 获取所有【横向对比与对标 (COMPARISON)】类工具。
     */
    public List<ToolDefinition> getComparisonTools() {
        return registry.values().stream()
                .filter(t -> "COMPARISON".equalsIgnoreCase(t.getScope()) || "MULTI_FUND_COMPARISON".equalsIgnoreCase(t.getScope()))
                .sorted(Comparator.comparing(t -> t.getSort() == null ? 99 : t.getSort()))
                .toList();
    }

    /**
     * 兼容别名：获取单基金分析工具。
     */
    public List<ToolDefinition> getSingleFundTools() {
        return getAnalysisTools();
    }

    /**
     * 兼容别名：获取多基金对比工具。
     */
    public List<ToolDefinition> getMultiFundTools() {
        return getComparisonTools();
    }

    /**
     * 按场景 Scope 查询 Tool。
     */
    public List<ToolDefinition> getByScope(String scope) {
        return registry.values().stream()
                .filter(t -> scope != null && scope.equalsIgnoreCase(t.getScope()))
                .sorted(Comparator.comparing(t -> t.getSort() == null ? 99 : t.getSort()))
                .toList();
    }

    /**
     * 跨所有工具查找特定组件定义。
     */
    public Optional<UITreeComponent> findComponent(String componentId) {
        if (componentId == null) return Optional.empty();
        for (ToolDefinition tool : registry.values()) {
            if (tool.getComponents() != null) {
                for (UITreeComponent c : tool.getComponents()) {
                    if (componentId.equals(c.getId())) {
                        return Optional.of(c);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 按业务分类查询 Tool。
     */
    public List<ToolDefinition> getByCategory(String categoryName) {
        return registry.values().stream()
                .filter(t -> categoryName != null && categoryName.equals(t.getCategory()))
                .toList();
    }

    /**
     * 获取已注册 Tool 数量。
     */
    public int size() {
        return registry.size();
    }
}
