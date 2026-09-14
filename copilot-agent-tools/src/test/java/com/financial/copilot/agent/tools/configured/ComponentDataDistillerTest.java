package com.financial.copilot.agent.tools.configured;

import com.financial.copilot.agent.tools.configured.distiller.ComponentDataDistiller;
import com.financial.copilot.agent.tools.configured.model.UITreeComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("方案 A 数据蒸馏器单元测试")
class ComponentDataDistillerTest {

    private ComponentDataDistiller distiller;

    @BeforeEach
    void setUp() {
        distiller = new ComponentDataDistiller();
    }

    @Test
    @DisplayName("750天海量时序净值折线图蒸馏测试 (压缩至关键金融特征与采样)")
    void testDistillTimeSeries() {
        // 模拟生成 750 个交易日的净值序列 (从 1.000 涨到 2.000，中间在第 300 天冲高到 2.500，第 400 天回调到 1.800)
        List<Map<String, Object>> timeSeries = new ArrayList<>();
        double currentNav = 1.000;
        for (int i = 0; i < 750; i++) {
            Map<String, Object> point = new HashMap<>();
            String date = String.format("2023-%02d-%02d", (i / 30) % 12 + 1, (i % 28) + 1);
            point.put("date", date);

            if (i < 300) {
                currentNav += 0.005; // 涨到 2.500
            } else if (i < 400) {
                currentNav -= 0.007; // 回撤到 1.800
            } else {
                currentNav += 0.002;
            }
            point.put("nav", currentNav);
            timeSeries.add(point);
        }

        UITreeComponent navChart = UITreeComponent.builder()
                .id("NetValueTrend")
                .name("净值走势")
                .cardTitle("近三年复权单位净值走势")
                .displayType("chart:line")
                .data(timeSeries)
                .build();

        String distilledText = distiller.distill(navChart);

        assertNotNull(distilledText);
        System.out.println("Distilled Time Series Text:\n" + distilledText);

        // 断言蒸馏出的核心金融量化特征
        assertTrue(distilledText.contains("时序量化特征"));
        assertTrue(distilledText.contains("750 个交易日"));
        assertTrue(distilledText.contains("起始净值"));
        assertTrue(distilledText.contains("最新净值"));
        assertTrue(distilledText.contains("区间累计回报"));
        assertTrue(distilledText.contains("最大回撤"));
        assertTrue(distilledText.contains("走势里程碑采样"));

        // 断言字数极度精简 (控制在 500 字以内，远低于原始 750 组坐标数据)
        assertTrue(distilledText.length() < 500, "Distilled text should be under 500 characters");
    }

    @Test
    @DisplayName("50行重仓股票长表格蒸馏测试 (截取Top10与合计权重汇总)")
    void testDistillTable() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("stockCode", String.format("600%03d", i));
            row.put("stockName", "持仓标的" + i);
            row.put("持仓占比(%)", 10.0 - i * 0.15);
            row.put("涨跌幅(%)", 2.5);
            rows.add(row);
        }

        List<Map<String, Object>> columns = List.of(
                Map.of("id", "stockCode", "title", "股票代码"),
                Map.of("id", "stockName", "title", "股票名称"),
                Map.of("id", "持仓占比(%)", "title", "持仓占比(%)"),
                Map.of("id", "涨跌幅(%)", "title", "涨跌幅(%)")
        );

        UITreeComponent holdingsTable = UITreeComponent.builder()
                .id("TopHoldings")
                .name("前十大重仓股票")
                .cardTitle("重仓股票明细")
                .displayType("table")
                .columns(columns)
                .data(rows)
                .build();

        String distilledText = distiller.distill(holdingsTable);

        assertNotNull(distilledText);
        System.out.println("Distilled Table Text:\n" + distilledText);

        assertTrue(distilledText.contains("前 10 项"));
        assertTrue(distilledText.contains("股票代码"));
        assertTrue(distilledText.contains("合计权重/占比"));
        assertTrue(distilledText.contains("其余 40 项完整明细已在工作台表格渲染展示"));
    }

    @Test
    @DisplayName("五维雷达图评分蒸馏测试")
    void testDistillRadar() {
        List<Map<String, Object>> rows = List.of(
                Map.of("收益能力", 85.5, "抗风险能力", 78.0, "选股能力", 92.0, "择时能力", 68.5, "基金公司", 88.0)
        );

        UITreeComponent radarChart = UITreeComponent.builder()
                .id("AbilityChart")
                .name("五维能力诊断")
                .cardTitle("基金五维诊断雷达图")
                .displayType("chart:radar")
                .data(rows)
                .build();

        String distilledText = distiller.distill(radarChart);

        assertNotNull(distilledText);
        System.out.println("Distilled Radar Text:\n" + distilledText);

        assertTrue(distilledText.contains("选股能力: 92.0分"));
        assertTrue(distilledText.contains("收益能力: 85.5分"));
    }
}
