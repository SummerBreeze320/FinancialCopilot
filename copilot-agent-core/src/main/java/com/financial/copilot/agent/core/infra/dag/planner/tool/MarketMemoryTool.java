package com.financial.copilot.agent.core.infra.dag.planner.tool;

import com.financial.copilot.agent.core.infra.memory.MemoryClient;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 市场与长期记忆检索工具 — 通过 MemoryClient 调用 Python 记忆服务，
 * 检索用户画像、相关事实和历史决策，供 GraphPlanner 定制化节点生成。
 */
@Slf4j
@Component
public class MarketMemoryTool {

    @Builder
    public record MemoryRetrievalResult(
            String sessionKey,
            List<String> relevantFacts,
            List<String> historicalDecisions
    ) {}

    private final MemoryClient memoryClient;

    public MarketMemoryTool() {
        this(null);
    }

    @Autowired
    public MarketMemoryTool(@Autowired(required = false) MemoryClient memoryClient) {
        this.memoryClient = memoryClient;
    }

    public MemoryRetrievalResult retrieveMemory(String sessionKey, String query, int maxCount) {
        if (memoryClient == null || sessionKey == null || sessionKey.isBlank()) {
            return new MemoryRetrievalResult(sessionKey, Collections.emptyList(), Collections.emptyList());
        }

        int limit = maxCount > 0 ? maxCount : 5;
        List<String> relevantFacts = memoryClient.searchMemory(query, limit);
        List<String> historicalDecisions = memoryClient.searchMemory(
                "历史投资决策 研报摘要 " + query, limit);

        log.debug("[MARKET-MEMORY-TOOL] session={}, facts={}, decisions={}",
                sessionKey, relevantFacts.size(), historicalDecisions.size());

        return MemoryRetrievalResult.builder()
                .sessionKey(sessionKey)
                .relevantFacts(relevantFacts != null ? relevantFacts : Collections.emptyList())
                .historicalDecisions(historicalDecisions != null ? historicalDecisions : Collections.emptyList())
                .build();
    }
}
