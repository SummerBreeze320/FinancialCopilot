package com.financial.copilot.data.graph.adapter;

import com.financial.copilot.domain.graph.entity.FundGraphNode;
import com.financial.copilot.domain.graph.entity.HoldingRelation;
import com.financial.copilot.domain.graph.entity.ManagerGraphNode;
import com.financial.copilot.domain.graph.entity.StockGraphNode;
import com.financial.copilot.domain.graph.port.FinancialGraphPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <h1>Neo4j 5.x 金融知识图谱数据访问适配器</h1>
 * 基于 {@link Neo4jClient} 执行参数化 Cypher 语句，提供毫秒级图拓扑检索。
 */
@Slf4j
@Repository
public class Neo4jFinancialGraphAdapter implements FinancialGraphPort {

    private final Neo4jClient neo4jClient;

    public Neo4jFinancialGraphAdapter(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Override
    public void upsertFund(FundGraphNode fund) {
        if (fund == null || fund.code() == null) return;
        String cypher = """
            MERGE (f:Fund {code: $code})
            SET f.name = $name,
                f.fundType = $fundType,
                f.establishmentDate = $establishmentDate
            """;
        neo4jClient.query(cypher)
                .bind(fund.code()).to("code")
                .bind(fund.name() != null ? fund.name() : "").to("name")
                .bind(fund.fundType() != null ? fund.fundType() : "").to("fundType")
                .bind(fund.establishmentDate() != null ? fund.establishmentDate().toString() : "").to("establishmentDate")
                .run();
    }

    @Override
    public void upsertManager(ManagerGraphNode manager) {
        if (manager == null || manager.name() == null) return;
        String cypher = """
            MERGE (m:Manager {name: $name})
            SET m.id = $id,
                m.gender = $gender,
                m.education = $education,
                m.workYears = $workYears
            """;
        neo4jClient.query(cypher)
                .bind(manager.name()).to("name")
                .bind(manager.id() != null ? manager.id() : "").to("id")
                .bind(manager.gender() != null ? manager.gender() : "").to("gender")
                .bind(manager.education() != null ? manager.education() : "").to("education")
                .bind(manager.workYears() != null ? manager.workYears() : 0).to("workYears")
                .run();
    }

    @Override
    public void upsertStock(StockGraphNode stock) {
        if (stock == null || stock.code() == null) return;
        String cypher = """
            MERGE (s:Stock {code: $code})
            SET s.name = $name,
                s.industry = $industry,
                s.marketCap = $marketCap
            """;
        neo4jClient.query(cypher)
                .bind(stock.code()).to("code")
                .bind(stock.name() != null ? stock.name() : "").to("name")
                .bind(stock.industry() != null ? stock.industry() : "").to("industry")
                .bind(stock.marketCap() != null ? stock.marketCap().doubleValue() : 0.0).to("marketCap")
                .run();
    }

    @Override
    public void linkManagerToFund(String managerName, String fundCode, LocalDate startDate, boolean isCurrent) {
        if (managerName == null || fundCode == null) return;
        String cypher = """
            MATCH (m:Manager {name: $managerName})
            MATCH (f:Fund {code: $fundCode})
            MERGE (m)-[r:MANAGES]->(f)
            SET r.startDate = $startDate,
                r.isCurrent = $isCurrent
            """;
        neo4jClient.query(cypher)
                .bind(managerName).to("managerName")
                .bind(fundCode).to("fundCode")
                .bind(startDate != null ? startDate.toString() : "").to("startDate")
                .bind(isCurrent).to("isCurrent")
                .run();
    }

    @Override
    public void linkFundHolding(String fundCode, String stockCode, BigDecimal ratio, String quarter) {
        if (fundCode == null || stockCode == null) return;
        String cypher = """
            MATCH (f:Fund {code: $fundCode})
            MATCH (s:Stock {code: $stockCode})
            MERGE (f)-[r:HOLDS {quarter: $quarter}]->(s)
            SET r.ratio = $ratio
            """;
        neo4jClient.query(cypher)
                .bind(fundCode).to("fundCode")
                .bind(stockCode).to("stockCode")
                .bind(quarter != null ? quarter : "LATEST").to("quarter")
                .bind(ratio != null ? ratio.doubleValue() : 0.0).to("ratio")
                .run();
    }

    @Override
    public List<HoldingRelation> findTopHoldingsByFund(String fundCode) {
        if (fundCode == null) return List.of();
        String cypher = """
            MATCH (f:Fund {code: $fundCode})-[r:HOLDS]->(s:Stock)
            RETURN f.code AS fundCode,
                   s.code AS stockCode,
                   s.name AS stockName,
                   r.ratio AS ratio,
                   r.quarter AS quarter
            ORDER BY r.ratio DESC
            """;
        return new ArrayList<>(neo4jClient.query(cypher)
                .bind(fundCode).to("fundCode")
                .fetchAs(HoldingRelation.class)
                .mappedBy((typeSystem, record) -> new HoldingRelation(
                        record.get("fundCode").asString(),
                        record.get("stockCode").asString(),
                        record.get("stockName").asString(""),
                        BigDecimal.valueOf(record.get("ratio").asDouble(0.0)),
                        record.get("quarter").asString("")
                ))
                .all());
    }

    @Override
    public List<String> findFundsByStock(String stockCode) {
        if (stockCode == null) return List.of();
        String cypher = """
            MATCH (f:Fund)-[r:HOLDS]->(s:Stock {code: $stockCode})
            RETURN f.code AS fundCode
            ORDER BY r.ratio DESC
            """;
        return new ArrayList<>(neo4jClient.query(cypher)
                .bind(stockCode).to("stockCode")
                .fetchAs(String.class)
                .mappedBy((typeSystem, record) -> record.get("fundCode").asString())
                .all());
    }

    @Override
    public List<String> findFundsByManager(String managerName) {
        if (managerName == null) return List.of();
        String cypher = """
            MATCH (m:Manager {name: $managerName})-[:MANAGES]->(f:Fund)
            RETURN f.code AS fundCode
            """;
        return new ArrayList<>(neo4jClient.query(cypher)
                .bind(managerName).to("managerName")
                .fetchAs(String.class)
                .mappedBy((typeSystem, record) -> record.get("fundCode").asString())
                .all());
    }

    @Override
    public List<String> findSharedHoldings(String fundCodeA, String fundCodeB) {
        if (fundCodeA == null || fundCodeB == null) return List.of();
        String cypher = """
            MATCH (f1:Fund {code: $fundCodeA})-[:HOLDS]->(s:Stock)<-[:HOLDS]-(f2:Fund {code: $fundCodeB})
            RETURN DISTINCT s.code AS stockCode
            """;
        return new ArrayList<>(neo4jClient.query(cypher)
                .bind(fundCodeA).to("fundCodeA")
                .bind(fundCodeB).to("fundCodeB")
                .fetchAs(String.class)
                .mappedBy((typeSystem, record) -> record.get("stockCode").asString())
                .all());
    }

    @Override
    public void clearAll() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    }
}
