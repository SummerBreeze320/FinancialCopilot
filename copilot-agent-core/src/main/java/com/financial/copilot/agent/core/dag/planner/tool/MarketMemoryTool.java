package com.financial.copilot.agent.core.dag.planner.tool;

import com.financial.copilot.agent.core.memory.LongTermMemoryService;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * <h1>市场与长期记忆检索工具 (MarketMemoryTool)</h1>
 * <p>
 * 供 {@code GraphPlanner} 在构建初始 DAG 或执行动态重规划时，跨会话检索用户的长期事实记忆、
 * 历史投资决策与风险画像约束，确保图规划不仅依赖当前单轮 Prompt，更能结合历史共识进行定制化节点生成。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class MarketMemoryTool {

    /**
     * 记忆检索结果载荷
     *
     * @param sessionId           会话标识
     * @param relevantFacts       与当前查询高度相关的提纯事实命题
     * @param historicalDecisions 历史已完成的决策或研报摘要
     */
    @Builder
    public record MemoryRetrievalResult(
            String sessionId,
            List<String> relevantFacts,
            List<String> historicalDecisions
    ) {}

    private final LongTermMemoryService longTermMemoryService;

    public MarketMemoryTool() {
        this(null);
    }

    @Autowired
    public MarketMemoryTool(@Autowired(required = false) LongTermMemoryService longTermMemoryService) {
        this.longTermMemoryService = longTermMemoryService;
    }

    /**
     * 依据当前用户提问和会话，检索最相关的长期投研偏好与历史决策
     *
     * @param sessionId 当前会话唯一标识
     * @param query     用户当轮投研需求
     * @param maxCount  最大召回条数
     * @return 记忆检索结果
     */
    public MemoryRetrievalResult retrieveMemory(String sessionId, String query, int maxCount) {
        if (longTermMemoryService == null || sessionId == null || sessionId.isBlank()) {
            return new MemoryRetrievalResult(sessionId, Collections.emptyList(), Collections.emptyList());
        }

        int limit = maxCount > 0 ? maxCount : 5;
        List<String> relevantFacts = longTermMemoryService.retrieveRelevantFacts(sessionId, query, limit);
        List<String> historicalDecisions = longTermMemoryService.retrieve(sessionId, limit);

        log.debug("[MARKET-MEMORY-TOOL] 成功召回记忆: session={}, facts={}, decisions={}",
                sessionId, relevantFacts.size(), historicalDecisions.size());

        return MemoryRetrievalResult.builder()
                .sessionId(sessionId)
                .relevantFacts(relevantFacts != null ? relevantFacts : Collections.emptyList())
                .historicalDecisions(historicalDecisions != null ? historicalDecisions : Collections.emptyList())
                .build();
    }
}
