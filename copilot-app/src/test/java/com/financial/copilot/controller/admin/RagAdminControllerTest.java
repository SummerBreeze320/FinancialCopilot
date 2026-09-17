package com.financial.copilot.controller.admin;

import com.financial.copilot.agent.tools.rag.FinancialSchemaRagTool;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.data.rag.service.RagSchemaSyncService;
import com.financial.copilot.domain.rag.port.RagGraphPort;
import com.financial.copilot.domain.rag.port.RagSchemaPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * <h1>金融知识库 RAG 与图谱管理控制器单元测试 (RagAdminControllerTest)</h1>
 * <p>
 * 验证 RAG 状态健康检查、全量同步流水线触发、三路混合召回探测及指标解释/板块展开调试接口。
 * </p>
 *
 * @author FinancialCopilot
 */
class RagAdminControllerTest {

    private RagSchemaPort mockSchemaPort;
    private RagGraphPort mockGraphPort;
    private RagSchemaSyncService mockSyncService;
    private FinancialSchemaRagTool mockSchemaRagTool;
    private RagAdminController controller;

    @BeforeEach
    void setUp() {
        mockSchemaPort = Mockito.mock(RagSchemaPort.class);
        mockGraphPort = Mockito.mock(RagGraphPort.class);
        mockSyncService = Mockito.mock(RagSchemaSyncService.class);
        mockSchemaRagTool = Mockito.mock(FinancialSchemaRagTool.class);

        controller = new RagAdminController(
                mockSchemaPort,
                Optional.of(mockGraphPort),
                mockSyncService,
                mockSchemaRagTool
        );
    }

    @Test
    @DisplayName("验证后台管理端查询 RAG 与 Neo4j 拓扑健康状态 (Neo4j 正常)")
    void testGetRagStatus_Neo4jAvailable() {
        when(mockSchemaPort.countMetrics()).thenReturn(45L);
        when(mockSchemaPort.countSectors()).thenReturn(112L);
        when(mockGraphPort.countMetricNodes()).thenReturn(45L);
        when(mockGraphPort.countSectorNodes()).thenReturn(112L);

        ApiResult<RagAdminController.RagStatusResponse> result = controller.getRagStatus();

        assertNotNull(result);
        assertEquals(200, result.getCode());
        RagAdminController.RagStatusResponse data = result.getData();
        assertEquals(45L, data.metricsInPostgres());
        assertEquals(112L, data.sectorsInPostgres());
        assertTrue(data.neo4jAvailable());
        assertEquals(45L, data.metricsInNeo4j());
        assertEquals(112L, data.sectorsInNeo4j());
        assertEquals(1024, data.vectorDimension());
        assertEquals("qwen3-embedding:0.6b", data.embeddingModel());
    }

    @Test
    @DisplayName("验证后台管理端查询 RAG 状态 (Neo4j 不可用或未接入)")
    void testGetRagStatus_Neo4jUnavailable() {
        RagAdminController noNeo4jController = new RagAdminController(
                mockSchemaPort,
                Optional.empty(),
                mockSyncService,
                mockSchemaRagTool
        );

        when(mockSchemaPort.countMetrics()).thenReturn(30L);
        when(mockSchemaPort.countSectors()).thenReturn(50L);

        ApiResult<RagAdminController.RagStatusResponse> result = noNeo4jController.getRagStatus();

        assertNotNull(result);
        assertEquals(200, result.getCode());
        RagAdminController.RagStatusResponse data = result.getData();
        assertEquals(30L, data.metricsInPostgres());
        assertEquals(50L, data.sectorsInPostgres());
        assertFalse(data.neo4jAvailable());
        assertEquals(0L, data.metricsInNeo4j());
    }

    @Test
    @DisplayName("验证后台管理端查询 RAG 状态 (Neo4j 发生异常时优雅降级)")
    void testGetRagStatus_Neo4jThrowsException() {
        when(mockSchemaPort.countMetrics()).thenReturn(10L);
        when(mockSchemaPort.countSectors()).thenReturn(20L);
        when(mockGraphPort.countMetricNodes()).thenThrow(new RuntimeException("Neo4j connection timeout"));

        ApiResult<RagAdminController.RagStatusResponse> result = controller.getRagStatus();

        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertFalse(result.getData().neo4jAvailable());
    }

    @Test
    @DisplayName("验证触发全量同步流水线 - 指定文件不存在时返回 400 校验错误")
    void testTriggerSync_FileNotFound() {
        RagAdminController.RagSyncRequest req = new RagAdminController.RagSyncRequest(
                "non_existent_metrics.json",
                "non_existent_sectors.json"
        );

        ApiResult<RagSchemaSyncService.SyncReport> result = controller.triggerSync(req);

        assertNotNull(result);
        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("验证触发全量同步流水线 - 正常文件成功处理")
    void testTriggerSync_Success() throws IOException {
        File tempMetrics = File.createTempFile("test_metrics_", ".json");
        File tempSectors = File.createTempFile("test_sectors_", ".json");
        tempMetrics.deleteOnExit();
        tempSectors.deleteOnExit();

        RagSchemaSyncService.SyncReport mockReport = new RagSchemaSyncService.SyncReport(
                20, 50, 1500L, true
        );
        when(mockSyncService.syncFromFiles(any(File.class), any(File.class))).thenReturn(mockReport);

        RagAdminController.RagSyncRequest req = new RagAdminController.RagSyncRequest(
                tempMetrics.getAbsolutePath(),
                tempSectors.getAbsolutePath()
        );

        ApiResult<RagSchemaSyncService.SyncReport> result = controller.triggerSync(req);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertEquals(20, result.getData().metricsCount());
        assertEquals(50, result.getData().sectorsCount());
        assertTrue(result.getData().neo4jSynced());
    }

    @Test
    @DisplayName("验证自然语言指标与板块混合召回探测接口")
    void testRecallTest() {
        // 1. 空查询校验
        ApiResult<String> failResult = controller.recallTest(new RagAdminController.RagRecallTestRequest("", 5));
        assertEquals(400, failResult.getCode());

        // 2. 正常探测
        when(mockSchemaRagTool.matchMetricsAndSectors("最大回撤小于10%的医药基金", 3))
                .thenReturn("{\"matchedMetrics\":[{\"mnemonic\":\"max_drawdown\"}]}");

        RagAdminController.RagRecallTestRequest validReq = new RagAdminController.RagRecallTestRequest(
                "最大回撤小于10%的医药基金", 3
        );
        ApiResult<String> successResult = controller.recallTest(validReq);

        assertNotNull(successResult);
        assertEquals(200, successResult.getCode());
        assertTrue(successResult.getData().contains("max_drawdown"));
    }

    @Test
    @DisplayName("验证指标助记码自然语言语义解释与拓扑推荐接口")
    void testExplainMetric() {
        // 1. 参数校验
        assertEquals(400, controller.explainMetric("").getCode());
        assertEquals(400, controller.explainMetric(null).getCode());

        // 2. 正常查询
        when(mockSchemaRagTool.explainMetric("sharpe_ratio"))
                .thenReturn("夏普比率衡量超额收益与波动风险");

        ApiResult<String> result = controller.explainMetric("sharpe_ratio");
        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertEquals("夏普比率衡量超额收益与波动风险", result.getData());
    }

    @Test
    @DisplayName("验证板块分类树递归展开探测接口")
    void testExpandSector() {
        // 1. 参数校验
        assertEquals(400, controller.expandSector("").getCode());

        // 2. 正常查询
        when(mockSchemaRagTool.expandSector("SEC_TECH"))
                .thenReturn("[\"半导体\", \"人工智能\", \"云计算\"]");

        ApiResult<String> result = controller.expandSector("SEC_TECH");
        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertTrue(result.getData().contains("半导体"));
    }
}
