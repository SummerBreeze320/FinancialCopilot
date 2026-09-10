package com.financial.copilot.agent.tools.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.common.fund.dto.FundMetricsDTO;
import com.financial.copilot.domain.fund.port.FundDataPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * <h1>公募基金量化指标体检工具 (Tool-as-Truth 核心工具)</h1>
 * <p>
 * 供多智能体系统（如 AnalyzerAgent、ComparatorAgent）强制调用，
 * 严格依据底层真实的每日复权净值时序，计算区间累计收益、年化回报、最大回撤、夏普比率、卡玛比率与持仓集中度。
 * 严禁大语言模型自行估算或心算金融数据，彻底杜绝幻觉。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundQuantAnalysisTool {

    private final FundDataPort fundDataPort;
    private final ObjectMapper objectMapper;

    public FundQuantAnalysisTool(FundDataPort fundDataPort, ObjectMapper objectMapper) {
        this.fundDataPort = fundDataPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取单只基金在指定统计区间的各项权威量化指标 (收益、回撤、夏普、卡玛、前十大集中度)
     *
     * @param fundCode  6位基金代码 (例如: 005827 或 161005)
     * @param startDate 起始日期 (格式: YYYY-MM-DD，可选，为空默认成立起)
     * @param endDate   截止日期 (格式: YYYY-MM-DD，可选，为空默认最新日期)
     * @return 格式化量化指标结果 JSON 字符串
     */
    public String getFundMetrics(String fundCode, String startDate, String endDate) {
        log.info("[TOOL CALL-FUND] 执行基金量化指标查询: fundCode={}, startDate={}, endDate={}", fundCode, startDate, endDate);

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
