package com.financial.copilot.agent.tools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 统一 Tool 元数据定义。
 * 遵循“第一层区分单基金深度分析 vs 多基金横向对比，第二层为具体 Tool 绑定一组对应组件”的架构规范。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {

    /** 工具唯一 ID (如 single_fund_profile, compare_basic_info, compare_brinson) */
    private String id;

    /** 工具中文名称 */
    private String name;

    /** 工具业务描述，供 LLM Tool Calling 理解调用时机 */
    private String description;

    /** 核心分类层级 (Scope): ANALYSIS (深度分析与画像) 或 COMPARISON (横向对比与对标) */
    private String scope;

    /** 工具类型：ANALYSIS_TOOL, COMPARISON_TOOL, AGENT_ANALYTICAL_TOOL */
    private String toolType;

    /** 所属分类名称 (如 资产配置, 收益与业绩, 收益归因) */
    private String category;

    /** 排序权重 */
    private Integer sort;

    /** 提供方类型：mcp, data_agent, http_invoke, local */
    private String provider;

    /** 对应 MCP Server 名称 (如 wind-fund-analysis) */
    private String server;

    /** 对应底层接口工具名 (如 fund_get_similar, fund_get_brinson_attribution) */
    private String targetTool;

    /** 对应 Data Agent 模板 ID (如 T609) */
    private String templateId;

    /** 底层 HTTP Command 指令 (如适用) */
    private String command;

    /** 日期口径模式：range, semiAnnualReportDate, monthEndWithYear */
    private String dateMode;

    /** 参数规格定义列表 */
    private List<ToolParameterDefinition> parameters;

    /** 该 Tool 执行时对应产生的一组组件数量 */
    private Integer componentCount;

    /** 该 Tool 绑定的一组组件列表 (Components) */
    private List<UITreeComponent> components;

    /** 原生扩展元数据 */
    private Map<String, Object> rawMetadata;
}
