package com.financial.copilot.agent.core.agents;

import com.financial.copilot.agent.core.agents.fund.FundComparatorAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>金融横向对标与对比专员统一门面 (Unified Comparator Agent Facade)</h1>
 * <p>
 * 职责：作为金融横向对标的统一调度门面。
 * 现阶段默认调度公募基金深度对标专员 {@link FundComparatorAgent}，
 * 在金融混合对比时可根据标的代码动态分流，为研报主编提供对称的定量与定性对标分析底座。
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
     * 构造函数，注入基金对比专员
     *
     * @param fundComparatorAgent 基金对比专员
     */
    public ComparatorAgent(FundComparatorAgent fundComparatorAgent) {
        this.fundComparatorAgent = fundComparatorAgent;
    }

    /**
     * 采集并对比两只公募基金的对称事实与深度归因
     *
     * @param codeA 标的A基金代码
     * @param codeB 标的B基金代码
     * @return 对称事实与归因分析 Markdown 文本
     */
    public String compareFunds(String codeA, String codeB) {
        log.info("[COMPARATOR-FACADE] 转发横向对标请求至基金对比专员: codeA={}, codeB={}", codeA, codeB);
        return fundComparatorAgent.compareFunds(codeA, codeB);
    }

    /**
     * 通用金融对标路由接口
     *
     * @param codeA 标的A代码
     * @param codeB 标的B代码
     * @return 深度对标分析报告
     */
    public String compareAssets(String codeA, String codeB) {
        log.info("[COMPARATOR-FACADE] 启动金融横向对标: codeA={}, codeB={}", codeA, codeB);
        // 当前默认派发至基金对比，未来可无缝分流股票对标
        return compareFunds(codeA, codeB);
    }
    public String compareFunds(String codeA, String codeB,
            java.util.function.Consumer<com.financial.copilot.agent.core.llm.dto.LlmResponse> usageConsumer) {
        return fundComparatorAgent.compareFunds(codeA, codeB, usageConsumer);
    }

    /**
     * 强类型 DAG 节点横向深度对标执行门面入口
     *
     * @param node  当前 DAG 节点
     * @param store 产物存储总线
     * @return 强类型横向对标报告产物
     */
    public com.financial.copilot.agent.core.dag.artifact.Artifact<com.financial.copilot.agent.core.dag.artifact.payload.ComparisonReport> compareArtifact(
            com.financial.copilot.agent.core.dag.model.GraphNode node,
            com.financial.copilot.agent.core.dag.artifact.ArtifactStore store
    ) {
        return compareArtifact(node, store, null);
    }

    public com.financial.copilot.agent.core.dag.artifact.Artifact<com.financial.copilot.agent.core.dag.artifact.payload.ComparisonReport> compareArtifact(
            com.financial.copilot.agent.core.dag.model.GraphNode node,
            com.financial.copilot.agent.core.dag.artifact.ArtifactStore store,
            java.util.function.Consumer<com.financial.copilot.agent.core.llm.dto.LlmResponse> usageConsumer
    ) {
        return fundComparatorAgent.compareArtifact(node, store, usageConsumer);
    }
}
