package com.financial.copilot.agent.core.agents;

import com.financial.copilot.agent.core.agents.fund.FundComparatorAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>横向对标专员门面 (Comparator Agent Facade)</h1>
 * <p>
 * 当前默认作为公募基金横向对标专员 {@link FundComparatorAgent} 的统一门面。
 * 在未来扩展场景下，支持根据双方标的代码类别分发至对应的资产对比专员。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class ComparatorAgent {

    /**
     * 公募基金横向对比专员
     */
    private final FundComparatorAgent fundComparatorAgent;

    /**
     * 构造函数
     *
     * @param fundComparatorAgent 基金对比专员
     */
    public ComparatorAgent(FundComparatorAgent fundComparatorAgent) {
        this.fundComparatorAgent = fundComparatorAgent;
    }

    /**
     * 采集双标的对称数据上下文
     *
     * @param codeA 标的A代码
     * @param codeB 标的B代码
     * @return 对称事实 Markdown 文本
     */
    public String compareFunds(String codeA, String codeB) {
        log.info("[COMPARATOR-FACADE] 转发横向对标请求至基金对比专员: codeA={}, codeB={}", codeA, codeB);
        return fundComparatorAgent.compareFunds(codeA, codeB);
    }
}
