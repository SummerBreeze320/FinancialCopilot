package com.financial.copilot.agent.core.agents;

import com.financial.copilot.agent.core.agents.fund.FundAnalyzerAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>多维全景体检与分析专员门面 (Analyzer Agent Facade)</h1>
 * <p>
 * 当前默认作为公募基金全景分析专员 {@link FundAnalyzerAgent} 的统一门面。
 * 在未来扩充股票个股体检、债券穿透或理财收益率分析时，可根据标的代码或资产类型派发至具体资产领域的专员。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class AnalyzerAgent {

    /**
     * 公募基金全景分析专员
     */
    private final FundAnalyzerAgent fundAnalyzerAgent;

    /**
     * 构造函数
     *
     * @param fundAnalyzerAgent 基金分析专员
     */
    public AnalyzerAgent(FundAnalyzerAgent fundAnalyzerAgent) {
        this.fundAnalyzerAgent = fundAnalyzerAgent;
    }

    /**
     * 采集并组织单只基金的全维度体检数据上下文
     *
     * @param fundCode 基金代码
     * @return 格式化后的体检事实文本
     */
    public String analyzeFund(String fundCode) {
        log.info("[ANALYZER-FACADE] 转发基金体检请求至基金分析专员: fundCode={}", fundCode);
        return fundAnalyzerAgent.analyzeFund(fundCode);
    }
}
