package com.financial.copilot.controller.admin;

import com.financial.copilot.agent.tools.rag.FinancialSchemaRagTool;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.data.rag.service.RagSchemaSyncService;
import com.financial.copilot.domain.rag.port.RagGraphPort;
import com.financial.copilot.domain.rag.port.RagSchemaPort;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.Optional;

/**
 * <h1>金融知识库 RAG 与图谱管理控制器 (RAG & Graph Admin Controller)</h1>
 * <p>
 * 专供算法研发、平台管理员和测试人员使用。提供：
 * <ul>
 *   <li>PostgreSQL (pgvector 1024维向量) 与 Neo4j 5.x 知识图谱数据量及拓扑健康状态查询；</li>
 *   <li>全量数据注入与向量化同步流水线触发 (/sync)；</li>
 *   <li>自然语言金融指标与板块召回联调实时探测 (/recall-test)。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/rag")
public class RagAdminController {

    @Builder
    public record RagStatusResponse(
            long metricsInPostgres,
            long sectorsInPostgres,
            boolean neo4jAvailable,
            long metricsInNeo4j,
            long sectorsInNeo4j,
            int vectorDimension,
            String embeddingModel
    ) {}

    public record RagRecallTestRequest(
            String query,
            Integer topK
    ) {}

    public record RagSyncRequest(
            String metricsPath,
            String sectorsPath
    ) {}

    private final RagSchemaPort schemaPort;
    private final Optional<RagGraphPort> graphPort;
    private final RagSchemaSyncService syncService;
    private final FinancialSchemaRagTool schemaRagTool;

    @Autowired
    public RagAdminController(
            RagSchemaPort schemaPort,
            Optional<RagGraphPort> graphPort,
            RagSchemaSyncService syncService,
            FinancialSchemaRagTool schemaRagTool
    ) {
        this.schemaPort = schemaPort;
        this.graphPort = graphPort;
        this.syncService = syncService;
        this.schemaRagTool = schemaRagTool;
    }

    /**
     * 查询当前金融知识库 RAG 与 Neo4j 图谱的数据量和拓扑状态
     */
    @GetMapping("/status")
    public ApiResult<RagStatusResponse> getRagStatus() {
        log.info("[ADMIN-RAG] 查询 RAG 知识库与图谱状态");

        long pgMetrics = schemaPort.countMetrics();
        long pgSectors = schemaPort.countSectors();
        boolean neo4jOk = graphPort.isPresent();
        long neo4jMetrics = 0L;
        long neo4jSectors = 0L;

        if (neo4jOk) {
            try {
                neo4jMetrics = graphPort.get().countMetricNodes();
                neo4jSectors = graphPort.get().countSectorNodes();
            } catch (Exception e) {
                log.warn("[ADMIN-RAG] 探测 Neo4j 拓扑节点数异常: {}", e.getMessage());
                neo4jOk = false;
            }
        }

        RagStatusResponse status = RagStatusResponse.builder()
                .metricsInPostgres(pgMetrics)
                .sectorsInPostgres(pgSectors)
                .neo4jAvailable(neo4jOk)
                .metricsInNeo4j(neo4jMetrics)
                .sectorsInNeo4j(neo4jSectors)
                .vectorDimension(1024)
                .embeddingModel("qwen3-embedding:0.6b")
                .build();

        return ApiResult.success(status);
    }

    /**
     * 触发全量指标与板块数据注入、本地 1024 维 Embedding 批量推理与双库同步流水线
     */
    @PostMapping("/sync")
    public ApiResult<RagSchemaSyncService.SyncReport> triggerSync(
            @RequestBody(required = false) RagSyncRequest request
    ) {
        log.info("[ADMIN-RAG] 收到触发全量 RAG 同步请求: {}", request);

        String mPath = (request != null && request.metricsPath() != null && !request.metricsPath().isBlank())
                ? request.metricsPath() : "docs/temp/metrics.json";
        String sPath = (request != null && request.sectorsPath() != null && !request.sectorsPath().isBlank())
                ? request.sectorsPath() : "docs/temp/sectors.json";

        File mFile = new File(mPath);
        File sFile = new File(sPath);

        if (!mFile.exists() || !sFile.exists()) {
            return ApiResult.fail(400, "指定的指标或板块数据文件不存在: metrics=" + mPath + ", sectors=" + sPath);
        }

        try {
            RagSchemaSyncService.SyncReport report = syncService.syncFromFiles(mFile, sFile);
            return ApiResult.success(report);
        } catch (Exception e) {
            log.error("[ADMIN-RAG] 全量同步执行失败", e);
            return ApiResult.fail(500, "全量同步失败: " + e.getMessage());
        }
    }

    /**
     * 在线联调测试自然语言三路混合召回
     */
    @PostMapping("/recall-test")
    public ApiResult<String> recallTest(@RequestBody RagRecallTestRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return ApiResult.fail(400, "查询 query 不能为空");
        }
        int topK = (request.topK() != null && request.topK() > 0) ? request.topK() : 5;
        String jsonResult = schemaRagTool.matchMetricsAndSectors(request.query(), topK);
        return ApiResult.success(jsonResult);
    }

    /**
     * 解释指标及其关联同类指标推荐测试
     */
    @GetMapping("/explain-metric")
    public ApiResult<String> explainMetric(@RequestParam String metric) {
        if (metric == null || metric.isBlank()) {
            return ApiResult.fail(400, "指标助记码或名称不能为空");
        }
        return ApiResult.success(schemaRagTool.explainMetric(metric));
    }

    /**
     * 板块分类树向下递归穿透展开测试
     */
    @GetMapping("/expand-sector")
    public ApiResult<String> expandSector(@RequestParam String sector) {
        if (sector == null || sector.isBlank()) {
            return ApiResult.fail(400, "板块 ID 或名称不能为空");
        }
        return ApiResult.success(schemaRagTool.expandSector(sector));
    }
}
