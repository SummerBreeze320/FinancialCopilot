package com.financial.copilot.domain.graph.port;

import com.financial.copilot.domain.graph.entity.FundGraphNode;
import com.financial.copilot.domain.graph.entity.HoldingRelation;
import com.financial.copilot.domain.graph.entity.ManagerGraphNode;
import com.financial.copilot.domain.graph.entity.StockGraphNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * <h1>金融知识图谱输出端口</h1>
 * 提供基金、经理、股票三元组及持仓穿透多跳图谱操作。
 */
public interface FinancialGraphPort {

    /**
     * 写入或更新基金节点
     */
    void upsertFund(FundGraphNode fund);

    /**
     * 写入或更新基金经理节点
     */
    void upsertManager(ManagerGraphNode manager);

    /**
     * 写入或更新股票节点
     */
    void upsertStock(StockGraphNode stock);

    /**
     * 建立基金经理管理基金关系 (:Manager)-[:MANAGES]->(:Fund)
     */
    void linkManagerToFund(String managerName, String fundCode, LocalDate startDate, boolean isCurrent);

    /**
     * 建立基金重仓持仓关系 (:Fund)-[:HOLDS]->(:Stock)
     */
    void linkFundHolding(String fundCode, String stockCode, BigDecimal ratio, String quarter);

    /**
     * 查询基金前十大重仓股票持仓清单
     */
    List<HoldingRelation> findTopHoldingsByFund(String fundCode);

    /**
     * 股票持仓穿透：查询哪些基金重仓了该股票
     */
    List<String> findFundsByStock(String stockCode);

    /**
     * 基金经理产品网络：查询指定经理名下管理的所有基金代码
     */
    List<String> findFundsByManager(String managerName);

    /**
     * 双基金重仓重合度对比：查询两只基金共同持有的股票代码列表
     */
    List<String> findSharedHoldings(String fundCodeA, String fundCodeB);

    /**
     * 清理测试图数据
     */
    void clearAll();
}
