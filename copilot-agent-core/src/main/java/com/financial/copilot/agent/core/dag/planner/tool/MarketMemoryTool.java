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
     * @param sessionKey           会话标识
     * @param relevantFacts       与当前查询高度相关的提纯事实命题
     * @param historicalDecisions 历史已完成的决策或研报摘要
     */
    @Builder
    public record MemoryRetrievalResult(
            String sessionKey,
            List<String> relevantFacts,
            List<String> historicalDecisions
    ) {}

    private final LongTermMemoryService longTermMemoryService;
    private final com.financial.copilot.agent.core.memory.remote.RemoteMemoryServiceClient remoteMemoryServiceClient;

    public MarketMemoryTool() {
        this(null, null);
    }

    public MarketMemoryTool(LongTermMemoryService longTermMemoryService) {
        this(longTermMemoryService, null);
    }

    @Autowired
    public MarketMemoryTool(@Autowired(required = false) LongTermMemoryService longTermMemoryService,
                            @Autowired(required = false) com.financial.copilot.agent.core.memory.remote.RemoteMemoryServiceClient remoteMemoryServiceClient) {
        this.longTermMemoryService = longTermMemoryService;
        this.remoteMemoryServiceClient = remoteMemoryServiceClient;
    }

    /**
     * 依据当前用户提问和会话，检索最相关的长期投研偏好与历史决策
     *
     * @param sessionKey 当前会话唯一标识
     * @param query     用户当轮投研需求
     * @param maxCount  最大召回条数
     * @return 记忆检索结果
     */
    public MemoryRetrievalResult retrieveMemory(String sessionKey, String query, int maxCount) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return new MemoryRetrievalResult(sessionKey, Collections.emptyList(), Collections.emptyList());
        }

        // 1. 尝试优先使用远程 Python 记忆微服务
        if (remoteMemoryServiceClient != null && remoteMemoryServiceClient.isEnabled()) {
            String userId = extractUserId(sessionKey);
            com.financial.copilot.agent.core.memory.remote.dto.RecallRequestDTO request =
                    com.financial.copilot.agent.core.memory.remote.dto.RecallRequestDTO.builder()
                            .userId(userId)
                            .queryText(query)
                            .taskType("investment_advisory")
                            .tokenBudget(500)
                            .build();

            java.util.Optional<com.financial.copilot.agent.core.memory.remote.dto.MemoryBundleDTO> bundleOpt =
                    remoteMemoryServiceClient.recall(request);
            if (bundleOpt.isPresent()) {
                com.financial.copilot.agent.core.memory.remote.dto.MemoryBundleDTO bundle = bundleOpt.get();
                List<String> facts = bundle.getRecalledItems() != null
                        ? bundle.getRecalledItems().stream().map(item -> item.getContent()).toList()
                        : Collections.emptyList();
                List<String> decisions = bundle.getCompactContext() != null && !bundle.getCompactContext().isBlank()
                        ? List.of(bundle.getCompactContext())
                        : Collections.emptyList();

                log.debug("[MARKET-MEMORY-TOOL] 远程长期记忆召回成功: session={}, items={}, tokens={}",
                        sessionKey, facts.size(), bundle.getTotalTokens());
                return MemoryRetrievalResult.builder()
                        .sessionKey(sessionKey)
                        .relevantFacts(facts)
                        .historicalDecisions(decisions)
                        .build();
            }
            log.debug("[MARKET-MEMORY-TOOL] 远程记忆服务未响应或超时，平滑降级至本地记忆存储: session={}", sessionKey);
        }

        // 2. 本地长期记忆服务兜底
        if (longTermMemoryService == null) {
            return new MemoryRetrievalResult(sessionKey, Collections.emptyList(), Collections.emptyList());
        }

        int limit = maxCount > 0 ? maxCount : 5;
        List<String> relevantFacts = longTermMemoryService.retrieveRelevantFacts(sessionKey, query, limit);
        List<String> historicalDecisions = longTermMemoryService.retrieve(sessionKey, limit);

        log.debug("[MARKET-MEMORY-TOOL] 成功召回本地记忆: session={}, facts={}, decisions={}",
                sessionKey, relevantFacts.size(), historicalDecisions.size());

        return MemoryRetrievalResult.builder()
                .sessionKey(sessionKey)
                .relevantFacts(relevantFacts != null ? relevantFacts : Collections.emptyList())
                .historicalDecisions(historicalDecisions != null ? historicalDecisions : Collections.emptyList())
                .build();
    }

    private String extractUserId(String sessionKey) {
        if (sessionKey != null && sessionKey.contains(":")) {
            return sessionKey.substring(0, sessionKey.indexOf(":"));
        }
        return sessionKey != null ? sessionKey : "default_user";
    }
}
