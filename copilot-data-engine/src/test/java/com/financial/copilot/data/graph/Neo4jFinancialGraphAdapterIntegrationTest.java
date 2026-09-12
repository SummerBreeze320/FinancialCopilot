package com.financial.copilot.data.graph;

import com.financial.copilot.data.graph.adapter.Neo4jFinancialGraphAdapter;
import com.financial.copilot.domain.graph.entity.FundGraphNode;
import com.financial.copilot.domain.graph.entity.HoldingRelation;
import com.financial.copilot.domain.graph.entity.ManagerGraphNode;
import com.financial.copilot.domain.graph.entity.StockGraphNode;
import org.junit.jupiter.api.*;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>Neo4j 5.x 金融知识图谱真实集成测试</h1>
 * 连通本地运行的 Docker Neo4j 5.26 容器 (bolt://localhost:7687)。
 */
@DisplayName("Neo4j 5.x 金融知识图谱真实集成测试")
class Neo4jFinancialGraphAdapterIntegrationTest {

    private static Driver driver;
    private Neo4jFinancialGraphAdapter adapter;

    @BeforeAll
    static void initDriver() {
        driver = GraphDatabase.driver("bolt://localhost:7687", AuthTokens.basic("neo4j", "123456"));
    }

    @AfterAll
    static void closeDriver() {
        if (driver != null) {
            driver.close();
        }
    }

    @BeforeEach
    void setUp() {
        Neo4jClient neo4jClient = Neo4jClient.create(driver);
        adapter = new Neo4jFinancialGraphAdapter(neo4jClient);
        adapter.clearAll();
    }

    @Test
    @DisplayName("测试经理-基金-股票三元组创建与多跳穿透查询")
    void testGraphOperationsAndPenetration() {
        // 1. 写入基金经理: 张坤
        adapter.upsertManager(new ManagerGraphNode("mgr_001", "张坤", "男", "硕士", 12));

        // 2. 写入基金: 005827 易方达蓝筹精选, 000001 华夏成长
        adapter.upsertFund(new FundGraphNode("005827", "易方达蓝筹精选混合", "偏股混合型", LocalDate.of(2018, 9, 5)));
        adapter.upsertFund(new FundGraphNode("000001", "华夏成长混合", "偏股混合型", LocalDate.of(2001, 12, 18)));

        // 3. 关联经理任职: 张坤 -> 005827
        adapter.linkManagerToFund("张坤", "005827", LocalDate.of(2018, 9, 5), true);

        // 4. 写入股票: 600519 贵州茅台, 000858 五粮液, 300750 宁德时代
        adapter.upsertStock(new StockGraphNode("600519", "贵州茅台", "白酒", BigDecimal.valueOf(2000000000000L)));
        adapter.upsertStock(new StockGraphNode("000858", "五粮液", "白酒", BigDecimal.valueOf(500000000000L)));
        adapter.upsertStock(new StockGraphNode("300750", "宁德时代", "新能源", BigDecimal.valueOf(800000000000L)));

        // 5. 关联基金持仓:
        // 005827 持有: 600519 (9.8%), 000858 (8.5%)
        adapter.linkFundHolding("005827", "600519", BigDecimal.valueOf(9.8), "2026Q2");
        adapter.linkFundHolding("005827", "000858", BigDecimal.valueOf(8.5), "2026Q2");

        // 000001 持有: 600519 (7.2%), 300750 (6.1%)
        adapter.linkFundHolding("000001", "600519", BigDecimal.valueOf(7.2), "2026Q2");
        adapter.linkFundHolding("000001", "300750", BigDecimal.valueOf(6.1), "2026Q2");

        // 验证 1: 经理旗下基金查询
        List<String> zhangKunFunds = adapter.findFundsByManager("张坤");
        assertEquals(1, zhangKunFunds.size());
        assertEquals("005827", zhangKunFunds.get(0));

        // 验证 2: 股票持仓穿透查询 (哪些基金重仓了茅台 600519)
        List<String> moutaiFunds = adapter.findFundsByStock("600519");
        assertEquals(2, moutaiFunds.size());
        assertTrue(moutaiFunds.contains("005827"));
        assertTrue(moutaiFunds.contains("000001"));

        // 验证 3: 基金重仓清单查询 (按持仓比例倒序)
        List<HoldingRelation> topHoldings = adapter.findTopHoldingsByFund("005827");
        assertEquals(2, topHoldings.size());
        assertEquals("600519", topHoldings.get(0).stockCode());
        assertEquals("000858", topHoldings.get(1).stockCode());

        // 验证 4: 双基金重仓重合度对比 (共同持有茅台 600519)
        List<String> sharedHoldings = adapter.findSharedHoldings("005827", "000001");
        assertEquals(1, sharedHoldings.size());
        assertEquals("600519", sharedHoldings.get(0));
    }
}
