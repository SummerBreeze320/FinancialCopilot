package com.financial.copilot.agent.tools.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.common.fund.dto.FundScreeningCriteria;
import com.financial.copilot.domain.fund.entity.FundInfo;
import com.financial.copilot.domain.fund.port.FundDataPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <h1>基金智能筛选只读工具 (Fund Screening Tool)</h1>
 * <p>
 * 为 ScreenerAgent 提供基金初筛能力。数据源设计为外部 HTTP 接口调用，本地数据库无需维护。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundScreeningTool {

    private final FundDataPort fundDataPort;
    private final ObjectMapper objectMapper;

    public FundScreeningTool(@Autowired(required = false) FundDataPort fundDataPort, ObjectMapper objectMapper) {
        this.fundDataPort = fundDataPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据多维筛选条件查询符合条件的公募基金池
     *
     * @param criteria 强类型选基 DSL 条件
     * @return 命中条件的基金标的列表 JSON
     */
    public String screenFunds(FundScreeningCriteria criteria) {
        log.info("[TOOL CALL-FUND] 执行多维选基: criteria={}", criteria);
        if (fundDataPort == null) {
            log.info("[TOOL CALL-FUND] 当前未配置本地基金数据库端口，待后续通过外部 HTTP 接口接入");
            return "[]";
        }

        try {
            List<FundInfo> funds = fundDataPort.screenFunds(criteria);
            return objectMapper.writeValueAsString(funds);
        } catch (Exception e) {
            log.error("执行基金筛选失败: criteria={}", criteria, e);
            return "{\"error\": \"筛选失败: " + e.getMessage() + "\"}";
        }
    }
}
