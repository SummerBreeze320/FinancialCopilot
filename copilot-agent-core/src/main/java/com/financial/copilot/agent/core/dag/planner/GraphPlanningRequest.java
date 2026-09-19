package com.financial.copilot.agent.core.dag.planner;

import com.financial.copilot.agent.core.llm.dto.LlmResponse;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * <h1>动态图规划请求载荷 (GraphPlanningRequest)</h1>
 * <p>
 * 携带当前轮次用户诉求、适格画像、以及跨多轮的短期上下文历史与滚动摘要。
 * </p>
 */
public record GraphPlanningRequest(
        String prompt,
        String sessionKey,
        UserInvestmentProfile profile,
        Consumer<LlmResponse> usageConsumer,
        boolean enableThinking,
        com.financial.copilot.agent.core.dag.runtime.GraphRunRequest runRequest,
        List<String> recentContext
) {
    public GraphPlanningRequest(String prompt, String sessionKey, UserInvestmentProfile profile,
                                Consumer<LlmResponse> usageConsumer, boolean enableThinking) {
        this(prompt, sessionKey, profile, usageConsumer, enableThinking, null, Collections.emptyList());
    }

    public GraphPlanningRequest(String prompt, String sessionKey, UserInvestmentProfile profile,
                                Consumer<LlmResponse> usageConsumer, boolean enableThinking,
                                com.financial.copilot.agent.core.dag.runtime.GraphRunRequest runRequest) {
        this(prompt, sessionKey, profile, usageConsumer, enableThinking, runRequest, Collections.emptyList());
    }
}
