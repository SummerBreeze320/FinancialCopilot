package com.financial.copilot.agent.tools.fund;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>基金定期报告定性策略观点检索工具</h1>
 * <p>
 * 遵循 HTTP 外部调用设计规范，后续待外部 HTTP 季报接口确定后接入实现。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundReportRetrieverTool {

    public FundReportRetrieverTool() {
    }

    /**
     * 获取指定基金最新的季度策略观点全文切片
     *
     * @param fundCode 6位基金代码
     * @return 格式化后的季报观点文本
     */
    public String getLatestQuarterlyReportView(String fundCode) {
        log.info("[TOOL CALL-FUND] 查询基金定性季报策略观点: fundCode={}", fundCode);
        return "【" + fundCode + " 季报策略观点】: 基金定性观点数据源待配置外部 HTTP 服务接口。";
    }
}
