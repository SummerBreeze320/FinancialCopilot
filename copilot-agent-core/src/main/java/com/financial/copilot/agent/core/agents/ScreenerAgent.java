package com.financial.copilot.agent.core.agents;

import com.financial.copilot.agent.core.agents.fund.FundScreenerAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>智能筛选专员门面 (Screener Agent Facade)</h1>
 * <p>
 * 当前版本作为公募基金筛选专员 {@link FundScreenerAgent} 的兼容门面。
 * 在未来扩展场景下（如股票筛选、期货筛选、理财筛选），可在此类中根据上下文资产类型调度具体的领域筛选专员。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class ScreenerAgent {

    /**
     * 公募基金领域筛选专员
     */
    private final FundScreenerAgent fundScreenerAgent;

    /**
     * 构造函数，注入公募基金筛选专员
     *
     * @param fundScreenerAgent 基金筛选代理
     */
    public ScreenerAgent(FundScreenerAgent fundScreenerAgent) {
        this.fundScreenerAgent = fundScreenerAgent;
    }

    /**
     * 执行自然语言标的筛选（当前默认路由至公募基金筛选）
     *
     * @param userPrompt 用户自然语言筛选需求
     * @return 命中标的的 JSON 数组文本
     */
    public String executeScreening(String userPrompt) {
        log.info("[SCREENER-FACADE] 正在将筛选请求转发至基金专员: prompt={}", userPrompt);
        return fundScreenerAgent.executeScreening(userPrompt);
    }
}
