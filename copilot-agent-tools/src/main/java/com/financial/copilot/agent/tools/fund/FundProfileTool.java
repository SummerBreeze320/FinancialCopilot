package com.financial.copilot.agent.tools.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.business.fund.entity.FundInfo;
import com.financial.copilot.domain.business.fund.port.FundDataPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <h1>公募基金全维属性档案查询业务工具 (Fund Profile Tool)</h1>
 * <p>
 * 为 Agent 与调度器提供针对公募基金全称、类型、管理机构及跟踪基准等档案检索能力。
 * 支持 6 位纯数字基金代码精准查询与中文/简称模糊匹配。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundProfileTool {

    private final FundDataPort fundDataPort;
    private final ObjectMapper objectMapper;

    public FundProfileTool(FundDataPort fundDataPort, ObjectMapper objectMapper) {
        this.fundDataPort = fundDataPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询基金全维档案信息
     *
     * @param query 6 位纯数字基金代码 (如 "005827") 或基金中文名称/关键词 (如 "易方达蓝筹精选" / "易方达蓝筹")
     * @return 格式化的 JSON 字符串
     */
    public String getFundProfile(String query) {
        log.info("[TOOL CALL-FUND-PROFILE] 查询基金档案: query={}", query);
        if (query == null || query.isBlank()) {
            return "{\"status\": \"ERROR\", \"message\": \"查询入参不能为空\"}";
        }

        String trimmed = query.trim();
        FundInfo matchedInfo = null;

        try {
            // 1. 若符合 6 位纯数字正则，优先精准直查
            if (trimmed.matches("^\\d{6}$")) {
                matchedInfo = fundDataPort.getFundByCode(trimmed).orElse(null);
            }

            // 2. 否则通过模糊搜索候选标的
            if (matchedInfo == null) {
                List<FundInfo> candidates = fundDataPort.searchFunds(trimmed, 5);
                if (candidates != null && !candidates.isEmpty()) {
                    matchedInfo = candidates.getFirst();
                }
            }

            if (matchedInfo == null) {
                return objectMapper.writeValueAsString(Map.of(
                        "status", "NOT_FOUND",
                        "message", "未检索到匹配的公募基金标的: " + query
                ));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "SUCCESS");
            result.put("fundCode", matchedInfo.getFundCode());
            result.put("fundName", matchedInfo.getFundName());
            result.put("fundType", matchedInfo.getFundType() != null ? matchedInfo.getFundType() : "混合型");
            result.put("managementCompany", matchedInfo.getManagementCompanyId());
            result.put("currentScaleBillion", matchedInfo.getCurrentScaleBillion());
            result.put("trackingBenchmark", matchedInfo.getTrackingBenchmark());
            result.put("establishmentDate", matchedInfo.getEstablishmentDate());

            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("[TOOL CALL-FUND-PROFILE] 基金档案检索异常: query={}", query, e);
            return "{\"status\": \"ERROR\", \"message\": \"检索失败: " + e.getMessage() + "\"}";
        }
    }
}
