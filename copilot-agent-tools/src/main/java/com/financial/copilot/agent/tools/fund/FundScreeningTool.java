package com.financial.copilot.agent.tools.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.common.dto.FundScreeningCriteria;
import com.financial.copilot.domain.entity.FundInfo;
import com.financial.copilot.domain.port.FundDataPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基金智能多维筛选工具
 * 归属: 基金专属领域 (Fund Domain)
 */
@Slf4j
@Component
public class FundScreeningTool {

    private final FundDataPort fundDataPort;
    private final ObjectMapper objectMapper;

    public FundScreeningTool(FundDataPort fundDataPort, ObjectMapper objectMapper) {
        this.fundDataPort = fundDataPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据多维条件执行基金初筛
     */
    public String screenFunds(FundScreeningCriteria criteria) {
        log.info("[TOOL CALL-FUND] 执行多维选基: criteria={}", criteria);

        try {
            List<FundInfo> funds = fundDataPort.screenFunds(criteria);
            return objectMapper.writeValueAsString(funds);
        } catch (Exception e) {
            log.error("执行基金筛选失败: criteria={}", criteria, e);
            return "{\"error\": \"筛选失败: " + e.getMessage() + "\"}";
        }
    }

    public String executeScreening(FundScreeningCriteria criteria) {
        return screenFunds(criteria);
    }
}
