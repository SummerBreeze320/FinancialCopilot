package com.financial.copilot.agent.core.infra.intent;

import com.financial.copilot.common.intent.IntentClassificationResult;
import com.financial.copilot.common.intent.IntentType;
import com.financial.copilot.common.intent.MatchingLayer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * <h1>轻量分类器与多轮上下文融合 (SlmIntentClassifier)</h1>
 * <p>融合前几轮会话摘要并输出分类置信度打分。</p>
 *
 * @author FinancialCopilot
 */
@Component
public class SlmIntentClassifier {

    public Optional<IntentClassificationResult> classify(String query, String contextSummary) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        String combined = (contextSummary != null ? contextSummary + " " : "") + query;

        // 上下文承接判断
        if (combined.contains("刚才那只") || combined.contains("深入分析它") || combined.contains("看一下它的持仓")) {
            return Optional.of(new IntentClassificationResult(
                    IntentType.FUND_DIAGNOSIS, 0.90, Map.of("contextFollowup", true), MatchingLayer.SLM, false, null));
        }

        if (combined.contains("换成股票") || combined.contains("股票池") || combined.contains("个股")) {
            return Optional.of(new IntentClassificationResult(
                    IntentType.STOCK_RESEARCH, 0.88, Map.of(), MatchingLayer.SLM, false, null));
        }

        return Optional.empty();
    }
}
