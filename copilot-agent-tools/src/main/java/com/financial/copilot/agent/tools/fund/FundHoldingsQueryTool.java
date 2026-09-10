package com.financial.copilot.agent.tools.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.fund.entity.FundQuarterlyHolding;
import com.financial.copilot.domain.fund.port.FundDataPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <h1>基金持仓穿透查询工具 (Fund Holdings Query Tool)</h1>
 * <p>
 * 职责：遵循 Tool-as-Truth 规范，为各类 Agent 提供基金季度前十大重仓股及行业配置的穿透明细。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundHoldingsQueryTool {

    private final FundDataPort fundDataPort;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数，自动注入基金数据访问端口与 JSON 序列化器
     *
     * @param fundDataPort 基金数据端口
     * @param objectMapper 对象映射器
     */
    public FundHoldingsQueryTool(FundDataPort fundDataPort, ObjectMapper objectMapper) {
        this.fundDataPort = fundDataPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询基金在指定报告期的前十大重仓股票与行业配置明细
     *
     * @param fundCode      6位基金代码
     * @param reportQuarter 报告期 (如 "2024Q2"，可为 null 则默认最新季度)
     * @return 季度持仓明细列表 JSON 格式
     */
    public String getTopHoldings(String fundCode, String reportQuarter) {
        log.info("[TOOL CALL-FUND] 查询基金持仓明细: fundCode={}, quarter={}", fundCode, reportQuarter);

        try {
            List<FundQuarterlyHolding> holdings = fundDataPort.getHoldings(fundCode, reportQuarter);
            return objectMapper.writeValueAsString(holdings);
        } catch (Exception e) {
            log.error("查询基金持仓失败: fundCode={}", fundCode, e);
            return "{\"error\": \"查询持仓失败: " + e.getMessage() + "\"}";
        }
    }
}
