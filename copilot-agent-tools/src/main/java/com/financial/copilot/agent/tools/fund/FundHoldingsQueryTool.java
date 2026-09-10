package com.financial.copilot.agent.tools.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.fund.entity.FundQuarterlyHolding;
import com.financial.copilot.domain.fund.port.FundDataPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <h1>公募基金季度持仓穿透查询工具</h1>
 * <p>
 * 供分析专员与对标专员调用，穿透查询公募基金前十大重仓股票及所属行业板块，
 * 用于识别基金持仓集中度、行业偏好与风格漂移度。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundHoldingsQueryTool {

    private final FundDataPort fundDataPort;
    private final ObjectMapper objectMapper;

    public FundHoldingsQueryTool(FundDataPort fundDataPort, ObjectMapper objectMapper) {
        this.fundDataPort = fundDataPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询基金在指定报告期的重仓股票与行业配置
     *
     * @param fundCode      6位基金代码
     * @param reportQuarter 报告期 (如 "2024Q2"，可留空默认最新)
     * @return 季度持仓明细列表 JSON 格式
     */
    public String getHoldings(String fundCode, String reportQuarter) {
        log.info("[TOOL CALL-FUND] 查询基金持仓明细: fundCode={}, quarter={}", fundCode, reportQuarter);

        try {
            List<FundQuarterlyHolding> holdings = fundDataPort.getHoldings(fundCode, reportQuarter);
            return objectMapper.writeValueAsString(holdings);
        } catch (Exception e) {
            log.error("查询基金持仓失败: fundCode={}", fundCode, e);
            return "{\"error\": \"查询持仓失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 获取前十大重仓股票及持仓行业 (兼容别名方法)
     *
     * @param fundCode      6位基金代码
     * @param reportQuarter 报告期
     * @return 季度持仓明细列表 JSON 格式
     */
    public String getTopHoldings(String fundCode, String reportQuarter) {
        return getHoldings(fundCode, reportQuarter);
    }
}
