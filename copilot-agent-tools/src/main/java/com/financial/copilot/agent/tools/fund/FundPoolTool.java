package com.financial.copilot.agent.tools.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.business.fund.entity.FundPoolItem;
import com.financial.copilot.domain.business.fund.port.FundPoolPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * <h1>基金池管理业务工具 (Fund Pool Tool)</h1>
 * <p>为 Agent 提供针对用户私有基金池的增加、删除、目标仓位更新及检索能力。</p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundPoolTool {

    private final FundPoolPort fundPoolPort;
    private final ObjectMapper objectMapper;

    public FundPoolTool(FundPoolPort fundPoolPort, ObjectMapper objectMapper) {
        this.fundPoolPort = fundPoolPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 添加标的至基金池
     */
    public String addFund(Long userId, String fundCode, String fundName, String poolType,
                          Double targetWeight, String reason, String tags) {
        log.info("[TOOL CALL-FUND-POOL] 添加基金至池: userId={}, code={}, type={}, weight={}",
                userId, fundCode, poolType, targetWeight);
        try {
            FundPoolItem item = FundPoolItem.builder()
                    .userId(userId)
                    .fundCode(fundCode)
                    .fundName(fundName)
                    .poolType(poolType != null ? poolType : "CORE")
                    .targetWeight(targetWeight != null ? BigDecimal.valueOf(targetWeight) : BigDecimal.ZERO)
                    .reason(reason)
                    .tags(tags)
                    .build();
            FundPoolItem saved = fundPoolPort.addOrUpdate(item);
            return objectMapper.writeValueAsString(Map.of(
                    "status", "SUCCESS",
                    "message", "已成功将基金 [" + fundCode + "] 加入基金池 [" + saved.getPoolType() + "]",
                    "item", saved
            ));
        } catch (Exception e) {
            log.error("添加基金池失败: code={}", fundCode, e);
            return "{\"status\": \"ERROR\", \"message\": \"添加失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 更新基金池中某只标的的目标仓位与理由
     */
    public String updateFund(Long userId, String fundCode, String poolType, Double targetWeight, String reason) {
        log.info("[TOOL CALL-FUND-POOL] 更新基金池配置: userId={}, code={}, weight={}", userId, fundCode, targetWeight);
        try {
            FundPoolItem item = FundPoolItem.builder()
                    .userId(userId)
                    .fundCode(fundCode)
                    .poolType(poolType != null ? poolType : "CORE")
                    .targetWeight(targetWeight != null ? BigDecimal.valueOf(targetWeight) : null)
                    .reason(reason)
                    .build();
            FundPoolItem updated = fundPoolPort.addOrUpdate(item);
            return objectMapper.writeValueAsString(Map.of(
                    "status", "SUCCESS",
                    "message", "基金 [" + fundCode + "] 仓位已更新至 " + targetWeight,
                    "item", updated
            ));
        } catch (Exception e) {
            log.error("更新基金池失败: code={}", fundCode, e);
            return "{\"status\": \"ERROR\", \"message\": \"更新失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 从基金池中剔除标的
     */
    public String removeFund(Long userId, String fundCode, String poolType) {
        log.info("[TOOL CALL-FUND-POOL] 移出基金: userId={}, code={}, type={}", userId, fundCode, poolType);
        try {
            boolean removed = fundPoolPort.remove(userId, fundCode, poolType);
            return objectMapper.writeValueAsString(Map.of(
                    "status", removed ? "SUCCESS" : "NOT_FOUND",
                    "message", removed ? "基金 [" + fundCode + "] 已移出基金池" : "未在池中找到该基金"
            ));
        } catch (Exception e) {
            log.error("移出基金失败: code={}", fundCode, e);
            return "{\"status\": \"ERROR\", \"message\": \"移除失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 清空基金池
     */
    public String clearPool(Long userId, String poolType) {
        log.warn("[TOOL CALL-FUND-POOL] 清空基金池: userId={}, type={}", userId, poolType);
        try {
            int cleared = fundPoolPort.clear(userId, poolType);
            return objectMapper.writeValueAsString(Map.of(
                    "status", "SUCCESS",
                    "message", "已清空基金池 [" + poolType + "], 共剔除 " + cleared + " 只标的",
                    "clearedCount", cleared
            ));
        } catch (Exception e) {
            log.error("清空基金池失败: type={}", poolType, e);
            return "{\"status\": \"ERROR\", \"message\": \"清空失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 查询基金池列表
     */
    public String queryPool(Long userId, String poolType) {
        log.info("[TOOL CALL-FUND-POOL] 查询基金池: userId={}, type={}", userId, poolType);
        try {
            List<FundPoolItem> items = fundPoolPort.listByUser(userId, poolType);
            return objectMapper.writeValueAsString(Map.of(
                    "status", "SUCCESS",
                    "total", items.size(),
                    "items", items
            ));
        } catch (Exception e) {
            log.error("查询基金池失败: userId={}", userId, e);
            return "{\"status\": \"ERROR\", \"message\": \"查询失败: " + e.getMessage() + "\"}";
        }
    }
}
