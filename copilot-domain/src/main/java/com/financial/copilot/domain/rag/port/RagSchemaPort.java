package com.financial.copilot.domain.rag.port;

import com.financial.copilot.domain.rag.entity.RagFundMetric;
import com.financial.copilot.domain.rag.entity.RagFundSector;
import com.financial.copilot.domain.rag.entity.SchemaRecallResult;

import java.util.List;
import java.util.Optional;

/**
 * <h1>PostgreSQL 关系与 PGVector 存储端口契约</h1>
 */
public interface RagSchemaPort {

    /**
     * 批量幂等保存或更新指标
     */
    void upsertMetrics(List<RagFundMetric> metrics);

    /**
     * 批量幂等保存或更新板块
     */
    void upsertSectors(List<RagFundSector> sectors);

    /**
     * 三路混合检索指标
     */
    List<SchemaRecallResult.MetricMatch> searchMetrics(String query, float[] queryVec, int topK);

    /**
     * 三路混合检索板块
     */
    List<SchemaRecallResult.SectorMatch> searchSectors(String query, float[] queryVec, int topK);

    /**
     * 根据助记码精确查询指标
     */
    Optional<RagFundMetric> findMetricByMnemonic(String mnemonic);

    /**
     * 根据板块 ID 精确查询板块
     */
    Optional<RagFundSector> findSectorById(String sectorId);

    /**
     * 统计数据库中有效指标数
     */
    long countMetrics();

    /**
     * 统计数据库中有效板块数
     */
    long countSectors();
}
