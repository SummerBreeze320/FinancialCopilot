package com.financial.copilot.agent.tools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 前端工作台交互式组件载荷实体 (UITree Component)。
 * 承载 ECharts 图表配置、表格行列数据、卡片元信息等，用于前端画布流式渲染。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UITreeComponent {

    /** 组件唯一标识，如 FundInfoForDefault, AssetAllocation, NetValueTrend */
    private String id;

    /** 组件显示名称 */
    private String name;

    /** 卡片标题 */
    private String cardTitle;

    /** 显示类型：table, MergedHeader, chart:stackedBar, chart:line, chart:bar, chart:radar, matrix, iframe */
    private String displayType;

    /** 所属分类名称，如 "资产配置", "收益与业绩" */
    private String category;

    /** 卡片栅格宽度 (12/24) */
    private Integer width;

    /** 图表/表格元数据定义 */
    private Map<String, Object> metadata;

    /** 表格列定义或图表系列配置 */
    private List<Map<String, Object>> columns;

    /** 实际渲染数据行 (表格行数据列表，或时序数据列表) */
    private List<Map<String, Object>> data;

    /** 图表专用 ECharts Option 结构体 (如果是图表类型) */
    private Map<String, Object> chartOption;

    /** 嵌套子组件链接 */
    private List<String> linkId;

    /** 外部嵌入页面 URL (如果是 iframe) */
    private String url;

    /** 绑定的远程调用指令 (如 MFCP.Report16Picker2.GetData) */
    private String command;

    /** 组件入参名称列表 (如 windCodes, reportDate) */
    private List<String> params;
}
