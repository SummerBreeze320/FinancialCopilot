package com.financial.copilot.agent.tools.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.graph.entity.HoldingRelation;
import com.financial.copilot.domain.graph.port.FinancialGraphPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * <h1>金融知识图谱多跳与持仓穿透查询工具 (Financial Graph Tool)</h1>
 * <p>
 * 遵循 Tool-as-Truth 规范，基于 Neo4j 提供：
 * 1. 双基金重仓重合度对比；
 * 2. 股票被哪些核心基金重仓的穿透检索；
 * 3. 基金经理在管产品图谱关系网。
 * </p>
 */
@Slf4j
@Component
public class FinancialGraphTool {

    private final FinancialGraphPort graphPort;
    private final ObjectMapper objectMapper;

    public FinancialGraphTool(FinancialGraphPort graphPort, ObjectMapper objectMapper) {
        this.graphPort = graphPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询两只基金的共同重仓股票列表
     */
    public String getSharedHoldings(String fundCodeA, String fundCodeB) {
        log.info("[TOOL CALL-GRAPH] 查询双基金重仓股交集: fundA={}, fundB={}", fundCodeA, fundCodeB);
        try {
            List<String> shared = graphPort.findSharedHoldings(fundCodeA, fundCodeB);
            return objectMapper.writeValueAsString(Map.of(
                    "fundCodeA", fundCodeA,
                    "fundCodeB", fundCodeB,
                    "sharedStockCodes", shared,
                    "overlapCount", shared.size()
            ));
        } catch (Exception e) {
            log.error("查询双基金重仓重合度失败: fundA={}, fundB={}", fundCodeA, fundCodeB, e);
            return "{\"error\": \"查询重合持仓失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 股票持仓穿透：查询重仓该股票的所有公募基金
     */
    public String getFundsHoldingStock(String stockCode) {
        log.info("[TOOL CALL-GRAPH] 穿透股票重仓基金: stockCode={}", stockCode);
        try {
            List<String> funds = graphPort.findFundsByStock(stockCode);
            return objectMapper.writeValueAsString(Map.of(
                    "stockCode", stockCode,
                    "holdingFunds", funds,
                    "fundCount", funds.size()
            ));
        } catch (Exception e) {
            log.error("股票穿透重仓基金失败: stockCode={}", stockCode, e);
            return "{\"error\": \"股票穿透失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 查询基金经理管理的所有公募基金
     */
    public String getFundsByManager(String managerName) {
        log.info("[TOOL CALL-GRAPH] 查询基金经理旗下产品图谱: manager={}", managerName);
        try {
            List<String> funds = graphPort.findFundsByManager(managerName);
            return objectMapper.writeValueAsString(Map.of(
                    "managerName", managerName,
                    "managedFunds", funds,
                    "totalCount", funds.size()
            ));
        } catch (Exception e) {
            log.error("查询基金经理旗下产品失败: manager={}", managerName, e);
            return "{\"error\": \"查询基金经理产品失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 查询基金在图数据库中的最新重仓清单
     */
    public String getTopHoldingsFromGraph(String fundCode) {
        log.info("[TOOL CALL-GRAPH] 从图谱查询基金重仓持仓: fundCode={}", fundCode);
        try {
            List<HoldingRelation> holdings = graphPort.findTopHoldingsByFund(fundCode);
            return objectMapper.writeValueAsString(holdings);
        } catch (Exception e) {
            log.error("从图谱查询基金持仓失败: fundCode={}", fundCode, e);
            return "{\"error\": \"图谱持仓查询失败: " + e.getMessage() + "\"}";
        }
    }
}
