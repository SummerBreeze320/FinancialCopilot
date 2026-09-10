package com.financial.copilot.agent.tools.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.common.fund.dto.FundScreeningCriteria;
import com.financial.copilot.domain.fund.entity.FundInfo;
import com.financial.copilot.domain.fund.port.FundDataPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <h1>基金智能筛选只读工具 (Fund Screening Tool)</h1>
 * <p>
 * 职责：遵循 Tool-as-Truth 规范，为 ScreenerAgent 提供只读的基金初筛能力。
 * 根据输入的结构化筛选条件 {@link FundScreeningCriteria}，返回匹配的基金实体列表 JSON。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundScreeningTool {

    private final FundDataPort fundDataPort;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数，自动装配领域数据端口与 JSON 转换器
     *
     * @param fundDataPort 基金数据端口
     * @param objectMapper JSON 转换器
     */
    public FundScreeningTool(FundDataPort fundDataPort, ObjectMapper objectMapper) {
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

        try {
            List<FundInfo> funds = fundDataPort.screenFunds(criteria);
            return objectMapper.writeValueAsString(funds);
        } catch (Exception e) {
            log.error("执行基金筛选失败: criteria={}", criteria, e);
            return "{\"error\": \"筛选失败: " + e.getMessage() + "\"}";
        }
    }
}
