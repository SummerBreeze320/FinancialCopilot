package com.financial.copilot.agent.core.agents;

import com.financial.copilot.agent.core.agents.fund.FundScreenerAgent;
import com.financial.copilot.agent.core.agents.stock.StockScreenerAgent;
import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.payload.FundPool;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>多资产智能筛选专员统一门面 (Unified Screener Agent Facade)</h1>
 * <p>
 * 职责：作为多资产（公募基金 Fund、股票 Stock 等）筛选专员的智能路由门面。
 * 能够根据用户自然语言意图中的资产特征（如识别出包含“股票”、“A股”、“个股”、“市盈率”等股票特征，
 * 或“基金”、“基金经理”、“偏股混合”等基金特征），精准路由派发给对应的底层资产筛选专员执行。
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
     * 股票领域多因子筛选专员
     */
    private final StockScreenerAgent stockScreenerAgent;

    /**
     * 构造函数，自动注入各资产领域的筛选专员
     *
     * @param fundScreenerAgent  公募基金筛选代理
     * @param stockScreenerAgent 股票筛选代理
     */
    public ScreenerAgent(FundScreenerAgent fundScreenerAgent, StockScreenerAgent stockScreenerAgent) {
        this.fundScreenerAgent = fundScreenerAgent;
        this.stockScreenerAgent = stockScreenerAgent;
    }

    /**
     * 执行自然语言标的筛选，支持多资产智能意图识别与动态派发
     *
     * @param userPrompt 用户自然语言筛选需求
     * @return 命中标的的 JSON 数组文本
     */
    public String executeScreening(String userPrompt) {
        if (isStockIntent(userPrompt)) {
            log.info("[SCREENER-FACADE] 识别到股票标的筛选意图，路由至股票筛选专员: prompt={}", userPrompt);
            return stockScreenerAgent.executeScreening(userPrompt);
        }

        log.info("[SCREENER-FACADE] 默认路由至公募基金筛选专员: prompt={}", userPrompt);
        return fundScreenerAgent.executeScreening(userPrompt);
    }

    /**
     * 强类型 DAG 节点筛选执行入口
     *
     * @param node       当前 DAG 节点
     * @param userPrompt 用户原始指令
     * @return 强类型标的池产物
     */
    public Artifact<FundPool> screenArtifact(GraphNode node, String userPrompt) {
        return fundScreenerAgent.screenArtifact(node, userPrompt);
    }

    /**
     * 判断是否属于股票资产领域的筛选意图
     *
     * @param prompt 用户提问文本
     * @return true 若命中股票典型关键词
     */
    private boolean isStockIntent(String prompt) {
        if (prompt == null) {
            return false;
        }
        String p = prompt.toLowerCase();
        return p.contains("股票") || p.contains("个股") || p.contains("a股")
                || p.contains("龙头股") || p.contains("白马股") || p.contains("股息率")
                || (p.contains("市盈率") && !p.contains("基金"));
    }
}
