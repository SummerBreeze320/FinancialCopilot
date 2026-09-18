package com.financial.copilot.data.rag.service;

import com.financial.copilot.data.rag.parser.MetricSectorDataParser;
import com.financial.copilot.domain.shared.rag.entity.RagFundMetric;
import com.financial.copilot.domain.shared.rag.entity.RagFundSector;
import com.financial.copilot.domain.shared.rag.port.RagEmbeddingPort;
import com.financial.copilot.domain.shared.rag.port.RagGraphPort;
import com.financial.copilot.domain.shared.rag.port.RagSchemaPort;
import lombok.RequiredArgsConstructor;
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
 * <p>
 * 串联数据预处理、本地 1024 维模型批量推理、PostgreSQL + pgvector 关系向量持久化以及 Neo4j 拓扑树同步：
 * <ul>
 *   <li>解析 <code>metrics.json</code> 与 <code>sectors.json</code>；</li>
 *   <li>调用 Ollama API 批量生成 1024 维 Embedding；</li>
 *   <li>使用 MyBatis-Plus 批量 Upsert 写入 PostgreSQL 表；</li>
 *   <li>在 Neo4j 中幂等创建 <code>:FundSector</code> 树与 <code>:FundMetric</code> 分类网。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagSchemaSyncService {

    /**
     * 同步任务执行结果报告
     *
     * @param metricsCount 成功处理的指标总数
     * @param sectorsCount 成功处理的板块总数
     * @param durationMs   全流水线执行耗时（毫秒）
     * @param neo4jSynced  是否成功同步至 Neo4j
     */
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

    /**
     * 基于本地磁盘 JSON 文件执行全量向量化与双库同步
     *
     * @param metricsFile 指标数据文件 (metrics.json)
     * @param sectorsFile 板块分类数据文件 (sectors.json)
     * @return 同步统计报告
     * @throws IOException 读取文件失败时抛出
     */
    public SyncReport syncFromFiles(File metricsFile, File sectorsFile) throws IOException {
        try (InputStream mIs = new FileInputStream(metricsFile);
             InputStream sIs = new FileInputStream(sectorsFile)) {
            return syncAll(mIs, sIs);
        }
    }

    /**
     * 从输入流执行端到端全量向量化与双库同步
     *
     * @param metricsStream 指标数据输入流
     * @param sectorsStream 板块分类数据输入流
     * @return 同步统计报告
     * @throws IOException 数据反序列化失败时抛出
     */
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

        // 4. 基于 MyBatis-Plus 批量写入 PostgreSQL
        log.info("[RAG-SYNC] 正在通过 MyBatis-Plus 批量写入 PostgreSQL (pgvector)...");
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
