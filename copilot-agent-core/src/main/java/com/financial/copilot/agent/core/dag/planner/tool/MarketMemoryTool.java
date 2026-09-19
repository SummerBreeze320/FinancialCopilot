package com.financial.copilot.agent.core.dag.planner.tool;

import com.financial.copilot.agent.core.memory.remote.RemoteMemoryServiceClient;
import com.financial.copilot.agent.core.memory.remote.dto.MemoryBundleDTO;
import com.financial.copilot.agent.core.memory.remote.dto.RecallRequestDTO;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * <h1>市场与长期记忆检索工具 (MarketMemoryTool)</h1>
 * <p>
 * 供 {@code GraphPlanner} 在构建初始 DAG 或执行动态重规划时，跨会话检索用户的长期事实记忆、
 * 历史投资决策与风险画像约束，完全对接 Python 长期记忆微服务。
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

    private final RemoteMemoryServiceClient remoteMemoryServiceClient;

    public MarketMemoryTool() {
        this(null);
    }

    @Autowired
    public MarketMemoryTool(@Autowired(required = false) RemoteMemoryServiceClient remoteMemoryServiceClient) {
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

        // 统一使用远程 Python 记忆微服务
        if (remoteMemoryServiceClient != null && remoteMemoryServiceClient.isEnabled()) {
            String userId = extractUserId(sessionKey);
            RecallRequestDTO request = RecallRequestDTO.builder()
                    .userId(userId)
                    .queryText(query)
                    .taskType("investment_advisory")
                    .tokenBudget(500)
                    .build();

            Optional<MemoryBundleDTO> bundleOpt = remoteMemoryServiceClient.recall(request);
            if (bundleOpt.isPresent()) {
                MemoryBundleDTO bundle = bundleOpt.get();
                List<String> facts = bundle.getRecalledItems() != null
                        ? bundle.getRecalledItems().stream().map(MemoryBundleDTO.RecalledItemDTO::getContent).toList()
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
            log.debug("[MARKET-MEMORY-TOOL] 远程记忆服务未响应或超时: session={}", sessionKey);
        }

        return new MemoryRetrievalResult(sessionKey, Collections.emptyList(), Collections.emptyList());
    }

    private String extractUserId(String sessionKey) {
        if (sessionKey != null && sessionKey.contains(":")) {
            return sessionKey.substring(0, sessionKey.indexOf(":"));
        }
        return sessionKey != null ? sessionKey : "default_user";
    }
}
