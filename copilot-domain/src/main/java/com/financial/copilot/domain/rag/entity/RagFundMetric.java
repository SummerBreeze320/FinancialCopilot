package com.financial.copilot.domain.rag.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <h1>基金指标 RAG 领域实体 (对应 metrics.json)</h1>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagFundMetric {

    /** 指标助记码 (主键)，如: f_return_1y, f_risk_maxdownside */
    private String mnemonic;

    /** 指标中文名，如: 近1年回报, 最大回撤 */
    private String indexName;

    /** 所属大类名称，如: 收益指标, 风险指标, 通用指标 */
    private String parentName;

    /** 业务定义及口径说明 */
    private String description;

    /** 预置丰富语料文本 (含名称、英文缩写及同义词口语词) */
    private String embeddingText;

    /** 1024 维向量 */
    private float[] embedding;

    /** 原始数据源指标 ID */
    private Long sourceIndicatorId;

    /** 支持的用法列表，如: ["filter", "sort"] */
    private List<String> supportedUsage;

    /** 适用产品类别 */
    private String applicableProducts;

    /** 别名/同义词数组，如: ["近一年收益", "近一年回报"] */
    private List<String> aliases;

    /** 版本号 */
    @Builder.Default
    private Integer version = 1;

    /** 是否启用 */
    @Builder.Default
    private boolean enabled = true;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
