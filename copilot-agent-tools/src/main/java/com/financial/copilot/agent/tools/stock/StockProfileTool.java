package com.financial.copilot.agent.tools.stock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.common.stock.dto.StockMetricsDTO;
import com.financial.copilot.domain.business.stock.entity.StockInfo;
import com.financial.copilot.domain.business.stock.port.StockDataPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <h1>股票全维属性档案查询业务工具 (Stock Profile Tool)</h1>
 * <p>
 * 为 Agent 与上层调度器提供针对 A 股标的的名称、代码、板块及核心估值指标查询能力。
 * 支持 6 位纯数字代码精准直查以及中文/简称模糊匹配。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class StockProfileTool {

    private final StockDataPort stockDataPort;
    private final ObjectMapper objectMapper;

    public StockProfileTool(StockDataPort stockDataPort, ObjectMapper objectMapper) {
        this.stockDataPort = stockDataPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询股票全维档案与关键量化指标
     *
     * @param query 6 位纯数字代码 (如 "600519") 或中文/简称关键词 (如 "贵州茅台" / "茅台")
     * @return 格式化的 JSON 字符串
     */
    public String getStockProfile(String query) {
        log.info("[TOOL CALL-STOCK-PROFILE] 查询股票档案: query={}", query);
        if (query == null || query.isBlank()) {
            return "{\"status\": \"ERROR\", \"message\": \"查询入参不能为空\"}";
        }

        String trimmed = query.trim();
        StockInfo matchedInfo = null;

        try {
            // 1. 若符合 6 位纯数字正则，优先精准直查
            if (trimmed.matches("^\\d{6}$")) {
                matchedInfo = stockDataPort.getStockByCode(trimmed).orElse(null);
            }

            // 2. 否则通过模糊搜索候选标的
            if (matchedInfo == null) {
                List<StockInfo> candidates = stockDataPort.searchStocks(trimmed, 5);
                if (candidates != null && !candidates.isEmpty()) {
                    matchedInfo = candidates.getFirst();
                }
            }

            if (matchedInfo == null) {
                return objectMapper.writeValueAsString(Map.of(
                        "status", "NOT_FOUND",
                        "message", "未检索到匹配的股票标的: " + query
                ));
            }

            // 3. 关联查取量化与估值指标
            StockMetricsDTO metrics = null;
            try {
                metrics = stockDataPort.getStockMetrics(matchedInfo.getStockCode());
            } catch (Exception e) {
                log.warn("[TOOL CALL-STOCK-PROFILE] 获取个股量化指标异常: code={}, err={}", matchedInfo.getStockCode(), e.getMessage());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "SUCCESS");
            result.put("stockCode", matchedInfo.getStockCode());
            result.put("stockName", matchedInfo.getStockName());
            result.put("exchange", matchedInfo.getExchange() != null ? matchedInfo.getExchange() : "SH");
            result.put("primarySector", matchedInfo.getPrimarySector() != null ? matchedInfo.getPrimarySector() : "未分类");
            result.put("secondarySector", matchedInfo.getSecondarySector());
            result.put("totalMarketCapBillion", matchedInfo.getTotalMarketCapBillion());

            if (metrics != null) {
                result.put("peTtm", metrics.getPeTtm());
                result.put("pb", metrics.getPb());
                result.put("roe", metrics.getRoe());
                result.put("dividendYield", metrics.getDividendYield());
            }

            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("[TOOL CALL-STOCK-PROFILE] 股票档案检索异常: query={}", query, e);
            return "{\"status\": \"ERROR\", \"message\": \"检索失败: " + e.getMessage() + "\"}";
        }
    }
}
