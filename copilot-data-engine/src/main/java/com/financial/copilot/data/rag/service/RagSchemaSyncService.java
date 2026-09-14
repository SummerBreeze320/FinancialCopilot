package com.financial.copilot.data.rag.service;

import com.financial.copilot.data.rag.parser.MetricSectorDataParser;
import com.financial.copilot.domain.rag.entity.RagFundMetric;
import com.financial.copilot.domain.rag.entity.RagFundSector;
import com.financial.copilot.domain.rag.port.RagEmbeddingPort;
import com.financial.copilot.domain.rag.port.RagGraphPort;
import com.financial.copilot.domain.rag.port.RagSchemaPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * <h1>指标与板块全量数据注入与向量化同步流水线服务</h1>
 */
@Slf4j
@Service
public class RagSchemaSyncService {

    public record SyncReport(
            int metricsCount,
            int sectorsCount,
            long durationMs,
            boolean neo4jSynced
    ) {}

    private final MetricSectorDataParser parser;
    private final RagEmbeddingPort embeddingPort;
    private final RagSchemaPort schemaPort;
    private final Optional<RagGraphPort> graphPort;

    public RagSchemaSyncService(
            MetricSectorDataParser parser,
            RagEmbeddingPort embeddingPort,
            RagSchemaPort schemaPort,
            Optional<RagGraphPort> graphPort
    ) {
        this.parser = parser;
        this.embeddingPort = embeddingPort;
        this.schemaPort = schemaPort;
        this.graphPort = graphPort;
    }

    public SyncReport syncFromFiles(File metricsFile, File sectorsFile) throws IOException {
        try (InputStream mIs = new FileInputStream(metricsFile);
             InputStream sIs = new FileInputStream(sectorsFile)) {
            return syncAll(mIs, sIs);
        }
    }

    public SyncReport syncAll(InputStream metricsStream, InputStream sectorsStream) throws IOException {
        long start = System.currentTimeMillis();
        log.info("[RAG-SYNC] 开始全量数据同步与向量化入库流水线...");

        // 1. 解析指标与板块
        List<RagFundMetric> metrics = parser.parseMetrics(metricsStream);
        List<RagFundSector> sectors = parser.parseSectors(sectorsStream);

        // 2. 批量生成指标 Embedding 向量 (169 条)
        log.info("[RAG-SYNC] 正在批量生成指标 Embedding (总数: {})...", metrics.size());
        List<String> metricTexts = metrics.stream().map(RagFundMetric::getEmbeddingText).toList();
        List<float[]> metricVectors = embeddingPort.batchEmbed(metricTexts);
        for (int i = 0; i < metrics.size(); i++) {
            metrics.get(i).setEmbedding(metricVectors.get(i));
        }

        // 3. 批量生成板块 Embedding 向量 (1712 条)
        log.info("[RAG-SYNC] 正在批量生成板块分类树 Embedding (总数: {})...", sectors.size());
        List<String> sectorTexts = sectors.stream().map(RagFundSector::getEmbeddingText).toList();
        List<float[]> sectorVectors = embeddingPort.batchEmbed(sectorTexts);
        for (int i = 0; i < sectors.size(); i++) {
            sectors.get(i).setEmbedding(sectorVectors.get(i));
        }

        // 4. 批量写入 PostgreSQL
        log.info("[RAG-SYNC] 正在批量写入 PostgreSQL (pgvector)...");
        schemaPort.upsertMetrics(metrics);
        schemaPort.upsertSectors(sectors);

        // 5. 写入 Neo4j 图数据库
        boolean neo4jDone = false;
        if (graphPort.isPresent()) {
            log.info("[RAG-SYNC] 正在同步 Neo4j 图数据库分类树与指标网络...");
            RagGraphPort gp = graphPort.get();
            gp.syncSectorGraph(sectors);
            gp.syncMetricGraph(metrics);
            neo4jDone = true;
        } else {
            log.warn("[RAG-SYNC] 未检测到 RagGraphPort，跳过 Neo4j 图谱同步");
        }

        long duration = System.currentTimeMillis() - start;
        log.info("[RAG-SYNC] 全量同步完成! metrics={}, sectors={}, neo4jSynced={}, 耗时={}ms",
                metrics.size(), sectors.size(), neo4jDone, duration);

        return new SyncReport(metrics.size(), sectors.size(), duration, neo4jDone);
    }
}
