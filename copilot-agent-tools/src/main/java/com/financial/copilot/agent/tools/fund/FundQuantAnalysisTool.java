package com.financial.copilot.agent.tools.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.common.fund.dto.FundMetricsDTO;
import com.financial.copilot.domain.fund.port.FundDataPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * <h1>公募基金量化指标查询工具</h1>
 * <p>
 * 供 AnalyzerAgent、ComparatorAgent 查询量化数据。数据源设计为外部 HTTP 接口调用。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundQuantAnalysisTool {

    private final FundDataPort fundDataPort;
    private final ObjectMapper objectMapper;

    public FundQuantAnalysisTool(@Autowired(required = false) FundDataPort fundDataPort, ObjectMapper objectMapper) {
        this.fundDataPort = fundDataPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取单只基金在指定统计区间的各项量化指标
     *
     * @param fundCode  6位基金代码
     * @param startDate 起始日期
     * @param endDate   截止日期
     * @return 格式化量化指标结果 JSON 字符串
     */
    public String getFundMetrics(String fundCode, String startDate, String endDate) {
        log.info("[TOOL CALL-FUND] 执行基金量化指标查询: fundCode={}, startDate={}, endDate={}", fundCode, startDate, endDate);
        if (fundDataPort == null) {
            log.info("[TOOL CALL-FUND] 当前未挂载本地基金数据库端口，待配置外部 HTTP 指标接口");
            return "{}";
        }

        try {
            LocalDate start = (startDate != null && !startDate.isBlank()) ? LocalDate.parse(startDate) : null;
            LocalDate end = (endDate != null && !endDate.isBlank()) ? LocalDate.parse(endDate) : null;

            FundMetricsDTO metrics = fundDataPort.getFundMetrics(fundCode, start, end);
            return objectMapper.writeValueAsString(metrics);
        } catch (Exception e) {
            log.error("获取基金量化指标失败: fundCode={}", fundCode, e);
            return "{\"error\": \"获取指标失败: " + e.getMessage() + "\"}";
        }
    }
}
