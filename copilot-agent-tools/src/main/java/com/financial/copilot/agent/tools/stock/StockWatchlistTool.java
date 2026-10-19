package com.financial.copilot.agent.tools.stock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.business.stock.entity.StockWatchlistItem;
import com.financial.copilot.domain.business.stock.port.StockWatchlistPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * <h1>自选股票管理业务工具 (Stock Watchlist Tool)</h1>
 * <p>为 Agent 提供针对用户私有自选股的加入、移除、目标价更新及分组检索能力。</p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class StockWatchlistTool {

    private final StockWatchlistPort stockWatchlistPort;
    private final ObjectMapper objectMapper;

    public StockWatchlistTool(StockWatchlistPort stockWatchlistPort, ObjectMapper objectMapper) {
        this.stockWatchlistPort = stockWatchlistPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 添加股票至自选
     */
    public String addStock(Long userId, String stockCode, String stockName, String groupName,
                           Double targetPrice, String notes, String priority) {
        log.info("[TOOL CALL-STOCK-WATCHLIST] 添加自选股: userId={}, code={}, group={}, targetPrice={}",
                userId, stockCode, groupName, targetPrice);
        try {
            StockWatchlistItem item = StockWatchlistItem.builder()
                    .userId(userId)
                    .stockCode(stockCode)
                    .stockName(stockName)
                    .groupName(groupName != null && !groupName.isBlank() ? groupName : "默认")
                    .targetPrice(targetPrice != null ? BigDecimal.valueOf(targetPrice) : null)
                    .notes(notes)
                    .priority(priority != null ? priority : "MEDIUM")
                    .build();
            StockWatchlistItem saved = stockWatchlistPort.addOrUpdate(item);
            return objectMapper.writeValueAsString(Map.of(
                    "status", "SUCCESS",
                    "message", "股票 [" + stockCode + "] 已成功加入自选分组 [" + saved.getGroupName() + "]",
                    "item", saved
            ));
        } catch (Exception e) {
            log.error("添加自选股失败: code={}", stockCode, e);
            return "{\"status\": \"ERROR\", \"message\": \"添加自选股失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 更新自选股票的目标价与关注说明
     */
    public String updateStock(Long userId, String stockCode, String groupName, Double targetPrice, String notes) {
        log.info("[TOOL CALL-STOCK-WATCHLIST] 更新自选股: userId={}, code={}, price={}", userId, stockCode, targetPrice);
        try {
            StockWatchlistItem item = StockWatchlistItem.builder()
                    .userId(userId)
                    .stockCode(stockCode)
                    .groupName(groupName != null && !groupName.isBlank() ? groupName : "默认")
                    .targetPrice(targetPrice != null ? BigDecimal.valueOf(targetPrice) : null)
                    .notes(notes)
                    .build();
            StockWatchlistItem updated = stockWatchlistPort.addOrUpdate(item);
            return objectMapper.writeValueAsString(Map.of(
                    "status", "SUCCESS",
                    "message", "自选股 [" + stockCode + "] 信息已更新",
                    "item", updated
            ));
        } catch (Exception e) {
            log.error("更新自选股失败: code={}", stockCode, e);
            return "{\"status\": \"ERROR\", \"message\": \"更新自选股失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 移除自选股票
     */
    public String removeStock(Long userId, String stockCode, String groupName) {
        log.info("[TOOL CALL-STOCK-WATCHLIST] 移除自选股: userId={}, code={}, group={}", userId, stockCode, groupName);
        try {
            boolean removed = stockWatchlistPort.remove(userId, stockCode, groupName);
            return objectMapper.writeValueAsString(Map.of(
                    "status", removed ? "SUCCESS" : "NOT_FOUND",
                    "message", removed ? "股票 [" + stockCode + "] 已移出自选股" : "未在自选列表中找到该股票"
            ));
        } catch (Exception e) {
            log.error("移除自选股失败: code={}", stockCode, e);
            return "{\"status\": \"ERROR\", \"message\": \"移除自选股失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 清空自选分组
     */
    public String clearWatchlist(Long userId, String groupName) {
        log.warn("[TOOL CALL-STOCK-WATCHLIST] 清空自选分组: userId={}, group={}", userId, groupName);
        try {
            int cleared = stockWatchlistPort.clear(userId, groupName);
            return objectMapper.writeValueAsString(Map.of(
                    "status", "SUCCESS",
                    "message", "已清空自选分组 [" + groupName + "], 共剔除 " + cleared + " 只股票",
                    "clearedCount", cleared
            ));
        } catch (Exception e) {
            log.error("清空自选股失败: group={}", groupName, e);
            return "{\"status\": \"ERROR\", \"message\": \"清空自选股失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 查询自选股列表
     */
    public String queryWatchlist(Long userId, String groupName) {
        log.info("[TOOL CALL-STOCK-WATCHLIST] 查询自选股: userId={}, group={}", userId, groupName);
        try {
            List<StockWatchlistItem> list = stockWatchlistPort.listByUser(userId, groupName);
            return objectMapper.writeValueAsString(Map.of(
                    "status", "SUCCESS",
                    "total", list.size(),
                    "items", list
            ));
        } catch (Exception e) {
            log.error("查询自选股失败: userId={}", userId, e);
            return "{\"status\": \"ERROR\", \"message\": \"查询自选股失败: " + e.getMessage() + "\"}";
        }
    }
}
