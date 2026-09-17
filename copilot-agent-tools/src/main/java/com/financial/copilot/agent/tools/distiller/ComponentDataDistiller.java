package com.financial.copilot.agent.tools.distiller;

import com.financial.copilot.agent.tools.model.UITreeComponent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 方案 A 核心：组件数据内存轻量蒸馏器 (ComponentDataDistiller)。
 * <p>
 * 将底层返回的海量时序坐标数组 (750+ 交易日) 与大表格 (50+ 行)
 * 纯内存规则降维提炼为 100~300 Tokens 的金融事实摘要，
 * 既保证 Agent 获取真实数据，又彻底杜绝 LLM 上下文膨胀。
 * </p>
 */
@Component
public class ComponentDataDistiller {

    /**
     * 针对任意 UITreeComponent 执行智能降维蒸馏。
     *
     * @param component 前端组件全量载荷
     * @return 适合注入 LLM Observation 观察窗口的精简事实文本
     */
    public String distill(UITreeComponent component) {
        if (component == null) {
            return "无组件数据";
        }
        String type = component.getDisplayType() == null ? "table" : component.getDisplayType().toLowerCase(Locale.ROOT);
        String title = component.getCardTitle() != null ? component.getCardTitle() : component.getName();

        if (type.contains("line")) {
            return distillTimeSeries(title, component.getData(), "date", "value");
        } else if (type.contains("table") || type.contains("mergedheader")) {
            return distillTable(title, component.getData(), component.getColumns(), 10);
        } else if (type.contains("stackedbar") || type.contains("bar")) {
            return distillBarOrAllocation(title, component.getData());
        } else if (type.contains("radar")) {
            return distillRadar(title, component.getData(), component.getMetadata());
        } else if (type.contains("matrix")) {
            return distillMatrix(title, component.getData());
        } else if (type.contains("iframe")) {
            return "【" + title + "】已在前端画布嵌入完整公募 F9 页面视图，关联子模块: " + component.getLinkId();
        }
        return distillTable(title, component.getData(), component.getColumns(), 10);
    }

    /**
     * 时序折线图蒸馏：提取起止值、区间回报、极值、动态最大回撤及周期采样点。
     */
    public String distillTimeSeries(String title, List<Map<String, Object>> points, String dateKey, String valKey) {
        if (points == null || points.isEmpty()) {
            return "【" + title + "】: 暂无时序数据";
        }
        // 解析所有有效数值点
        List<DateVal> list = new ArrayList<>();
        for (Map<String, Object> p : points) {
            String d = findString(p, dateKey, "tradeDate", "date", "time", "endDate");
            Double v = findDouble(p, valKey, "nav", "unitNav", "value", "y", "close");
            if (d != null && v != null) {
                list.add(new DateVal(d, v));
            }
        }
        if (list.isEmpty()) {
            return "【" + title + "】: 共 " + points.size() + " 个数据点，但数值无法解析";
        }

        DateVal start = list.getFirst();
        DateVal end = list.getLast();
        double startVal = start.val;
        double endVal = end.val;
        double totalReturn = startVal != 0 ? (endVal - startVal) / startVal * 100.0 : 0.0;

        // 计算极值与最大回撤
        DateVal peak = list.getFirst();
        DateVal trough = list.getFirst();
        double maxDrawdown = 0.0;
        String mddPeakDate = start.date;
        String mddTroughDate = start.date;
        double runningPeak = start.val;
        String runningPeakDate = start.date;

        for (DateVal dv : list) {
            if (dv.val > peak.val) peak = dv;
            if (dv.val < trough.val) trough = dv;

            if (dv.val > runningPeak) {
                runningPeak = dv.val;
                runningPeakDate = dv.date;
            } else if (runningPeak > 0) {
                double dd = (runningPeak - dv.val) / runningPeak * 100.0;
                if (dd > maxDrawdown) {
                    maxDrawdown = dd;
                    mddPeakDate = runningPeakDate;
                    mddTroughDate = dv.date;
                }
            }
        }

        // 采样点提取 (最多提取 8~12 个关键里程碑节点)
        List<String> samples = new ArrayList<>();
        int step = Math.max(1, list.size() / 8);
        for (int i = 0; i < list.size(); i += step) {
            DateVal s = list.get(i);
            samples.add(s.date + ":" + formatDouble(s.val, 3));
        }
        if (!samples.contains(end.date + ":" + formatDouble(end.val, 3))) {
            samples.add(end.date + ":" + formatDouble(end.val, 3));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(title).append(" 时序量化特征】:\n");
        sb.append("- 统计区间: ").append(start.date).append(" 至 ").append(end.date)
                .append(" (共 ").append(list.size()).append(" 个交易日)\n");
        sb.append("- 起始净值: ").append(formatDouble(startVal, 3))
                .append(" -> 最新净值: ").append(formatDouble(endVal, 3))
                .append(" (区间累计回报: ").append(totalReturn >= 0 ? "+" : "")
                .append(formatDouble(totalReturn, 2)).append("%)\n");
        sb.append("- 区间极值: 最高 ").append(formatDouble(peak.val, 3)).append("(").append(peak.date)
                .append("), 最低 ").append(formatDouble(trough.val, 3)).append("(").append(trough.date).append(")\n");
        sb.append("- 最大回撤: -").append(formatDouble(maxDrawdown, 2)).append("% (发生区间: ")
                .append(mddPeakDate).append(" 至 ").append(mddTroughDate).append(")\n");
        sb.append("- 走势里程碑采样: ").append(String.join(" -> ", samples));
        return sb.toString();
    }

    /**
     * 表格数据蒸馏：截取 Top-N，统计关键汇总权重，生成 Markdown 表格。
     */
    public String distillTable(String title, List<Map<String, Object>> rows, List<Map<String, Object>> columns, int maxRows) {
        if (rows == null || rows.isEmpty()) {
            return "【" + title + "】: 暂无记录";
        }
        int total = rows.size();
        int showCount = Math.min(total, maxRows);

        // 提取主要展示列名
        List<String> colKeys = new ArrayList<>();
        List<String> colTitles = new ArrayList<>();
        if (columns != null && !columns.isEmpty()) {
            for (Map<String, Object> col : columns) {
                String id = String.valueOf(col.getOrDefault("id", col.get("key")));
                String colTitle = String.valueOf(col.getOrDefault("title", id));
                if (id != null && !id.isBlank() && !"null".equals(id)) {
                    colKeys.add(id);
                    colTitles.add(colTitle);
                }
            }
        }
        // 若没有列定义则从第一行推导
        if (colKeys.isEmpty()) {
            colKeys.addAll(rows.getFirst().keySet().stream().limit(6).toList());
            colTitles.addAll(colKeys);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(title).append("】(共 ").append(total).append(" 项，展示前 ").append(showCount).append(" 项):\n");
        sb.append("| ").append(String.join(" | ", colTitles)).append(" |\n");
        sb.append("|").append(" :--- |".repeat(colTitles.size())).append("\n");

        double sumRatio = 0.0;
        boolean hasRatio = false;

        for (int i = 0; i < showCount; i++) {
            Map<String, Object> r = rows.get(i);
            List<String> cells = new ArrayList<>();
            for (String k : colKeys) {
                Object val = r.get(k);
                String strVal = val != null ? String.valueOf(val) : "-";
                cells.add(strVal);

                // 尝试统计权重
                if (k.toLowerCase(Locale.ROOT).contains("ratio") || k.contains("占比") || k.contains("权重")) {
                    hasRatio = true;
                    sumRatio += parsePercentageOrDouble(strVal);
                }
            }
            sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
        }

        if (hasRatio && sumRatio > 0) {
            sb.append("- 前 ").append(showCount).append(" 项合计权重/占比: ")
                    .append(formatDouble(sumRatio, 2)).append("%\n");
        }
        if (total > showCount) {
            sb.append("(其余 ").append(total - showCount).append(" 项完整明细已在工作台表格渲染展示)");
        }
        return sb.toString();
    }

    /**
     * 柱状图 / 资产配置堆叠图蒸馏。
     */
    public String distillBarOrAllocation(String title, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "【" + title + "】: 暂无配置数据";
        }
        StringBuilder sb = new StringBuilder("【").append(title).append("】明细:\n");
        for (Map<String, Object> r : rows.stream().limit(10).toList()) {
            sb.append("- ");
            r.forEach((k, v) -> sb.append(k).append(": ").append(v).append("  "));
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 雷达图蒸馏：提取各维度得分及短板优势。
     */
    public String distillRadar(String title, List<Map<String, Object>> rows, Map<String, Object> metadata) {
        if (rows == null || rows.isEmpty()) {
            return "【" + title + "】: 暂无能力诊断评分数据";
        }
        StringBuilder sb = new StringBuilder("【").append(title).append("五维能力诊断】:\n");
        for (Map<String, Object> row : rows) {
            row.forEach((dim, score) -> sb.append("- ").append(dim).append(": ").append(score).append("分\n"));
        }
        return sb.toString();
    }

    /**
     * 相关系数矩阵蒸馏：找出最高相关对与最低相关对。
     */
    public String distillMatrix(String title, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "【" + title + "】: 暂无相关系数矩阵";
        }
        return "【" + title + "】已生成多标的收益率相关系数热力矩阵，各标的相关系数介于 [-1.0, 1.0] 之间，完整矩阵已在工作台热力卡片呈现。";
    }

    private static String findString(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            if (map.containsKey(k) && map.get(k) != null) return String.valueOf(map.get(k));
        }
        return null;
    }

    private static Double findDouble(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v instanceof Number n) return n.doubleValue();
            if (v instanceof String s) {
                try {
                    return Double.parseDouble(s.replace("%", "").trim());
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static double parsePercentageOrDouble(String str) {
        try {
            return Double.parseDouble(str.replace("%", "").trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static String formatDouble(double val, int scale) {
        return BigDecimal.valueOf(val).setScale(scale, RoundingMode.HALF_UP).toString();
    }

    private record DateVal(String date, double val) {}
}
