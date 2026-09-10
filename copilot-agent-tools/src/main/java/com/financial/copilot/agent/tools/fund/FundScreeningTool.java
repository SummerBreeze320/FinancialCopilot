package com.financial.copilot.agent.tools.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.common.fund.dto.FundScreeningCriteria;
import com.financial.copilot.domain.fund.entity.FundInfo;
import com.financial.copilot.domain.fund.port.FundDataPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <h1>公募基金智能多维筛选工具</h1>
 * <p>
 * 将 ScreenerAgent 结构化解析出的选基条件注入底层数据访问层执行，获取命中候选标的池。
 * </p>
 *
 * @author FinancialCopilot
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
     * 根据多维定量与行业条件执行基金初筛
     *
     * @param criteria 强类型选基 DSL 条件
     * @return 命中条件的基金标的列表 JSON
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

    /**
     * 别名方法：兼容不同调用约定
     *
     * @param criteria 强类型选基 DSL 条件
     * @return 命中条件的基金标的列表 JSON
     */
    public String executeScreening(FundScreeningCriteria criteria) {
        return screenFunds(criteria);
    }
}
