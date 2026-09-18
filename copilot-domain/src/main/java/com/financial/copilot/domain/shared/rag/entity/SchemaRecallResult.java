package com.financial.copilot.domain.shared.rag.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * <h1>指标与板块联合召回聚合结果</h1>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaRecallResult {

    /** 原始查询 Query */
    private String query;

    /** 命中的指标候选列表 */
    @Builder.Default
    private List<MetricMatch> matchedMetrics = Collections.emptyList();

    /** 命中的板块候选列表 */
    @Builder.Default
    private List<SectorMatch> matchedSectors = Collections.emptyList();

    /** 指标命中记录 */
    public record MetricMatch(
            RagFundMetric metric,
            double score,
            String category
    ) {}

    /** 板块命中记录 (含 Neo4j 图谱展开的子叶子板块 ID) */
    public record SectorMatch(
            RagFundSector sector,
            double score,
            List<String> expandedLeafIds
    ) {}
}
