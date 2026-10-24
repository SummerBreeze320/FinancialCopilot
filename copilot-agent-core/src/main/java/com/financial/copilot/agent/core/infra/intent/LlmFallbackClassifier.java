package com.financial.copilot.agent.core.infra.intent;

import com.financial.copilot.common.intent.IntentClassificationResult;
import com.financial.copilot.common.intent.IntentType;
import com.financial.copilot.common.intent.MatchingLayer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * <h1>LLM 深度语义兜底分类器 (LlmFallbackClassifier)</h1>
 * <p>负责长尾复杂语义推理、歧义澄清及精准 OOD 域外泛化拒识。</p>
 *
 * @author FinancialCopilot
 */
@Component
public class LlmFallbackClassifier {

    private static final List<String> OOD_KEYWORDS = List.of(
            "天气", "做菜", "食谱", "编写代码", "写一段代码", "写个程序", "讲个笑话",
            "谁最帅", "旅游", "电影", "游戏", "翻译", "诗歌", "小说"
    );

    private static final String DEFAULT_OOD_CLARIFICATION =
            "我是智能投研助手，专注于公募基金诊断、标的筛选、大类资产配置与研报生成。请提供基金代码或具体的金融投资需求。";

    public IntentClassificationResult classify(String query, String contextSummary) {
        if (query == null || query.isBlank()) {
            return new IntentClassificationResult(
                    IntentType.OUT_OF_DOMAIN, 1.0, Map.of(), MatchingLayer.LLM_FALLBACK, true, DEFAULT_OOD_CLARIFICATION
            );
        }

        String lower = query.trim().toLowerCase();

        // 域外 (OOD) 意图拒识
        boolean isOod = OOD_KEYWORDS.stream().anyMatch(lower::contains);
        if (isOod) {
            return new IntentClassificationResult(
                    IntentType.OUT_OF_DOMAIN,
                    0.95,
                    Map.of("ood_detected", true),
                    MatchingLayer.LLM_FALLBACK,
                    true,
                    DEFAULT_OOD_CLARIFICATION
            );
        }

        // 帮助咨询
        if (lower.contains("帮助") || lower.contains("使用说明") || lower.contains("你能做什么") || lower.contains("功能介绍")) {
            return new IntentClassificationResult(
                    IntentType.SYSTEM_HELP,
                    0.88,
                    Map.of(),
                    MatchingLayer.LLM_FALLBACK,
                    false,
                    null
            );
        }

        // 默认兜底至标的深度诊断
        return new IntentClassificationResult(
                IntentType.FUND_DIAGNOSIS,
                0.75,
                Map.of("fallback", true),
                MatchingLayer.LLM_FALLBACK,
                false,
                null
        );
    }
}
