package com.financial.copilot.agent.core.infra.intent;

import com.financial.copilot.common.intent.IntentClassificationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * <h1>分层级联意图识别流水线 (IntentRecognitionPipeline)</h1>
 * <p>按顺序路由：规则匹配层 (&lt;5ms) -&gt; SLM 轻量分类层 (带上下文融合，置信度阈值 0.85) -&gt; LLM 语义兜底层 (含 OOD 拒识)。</p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRecognitionPipeline {

    private static final double SLM_CONFIDENCE_THRESHOLD = 0.85;

    private final RuleIntentMatcher ruleMatcher;
    private final SlmIntentClassifier slmClassifier;
    private final LlmFallbackClassifier llmClassifier;

    /**
     * 级联执行意图识别
     *
     * @param query 当前用户输入文本
     * @param contextSummary 多轮上下文摘要（若无则为 null）
     * @return 最终意图分类结果
     */
    public IntentClassificationResult recognize(String query, String contextSummary) {
        log.debug("Starting intent recognition pipeline for query: {}", query);

        // 1. 规则匹配层短路 (0 Token, < 5ms)
        Optional<IntentClassificationResult> ruleResult = ruleMatcher.match(query);
        if (ruleResult.isPresent()) {
            log.info("Rule layer matched intent: {} for query: {}", ruleResult.get().intent(), query);
            return ruleResult.get();
        }

        // 2. SLM 轻量分类层与上下文融合
        Optional<IntentClassificationResult> slmResult = slmClassifier.classify(query, contextSummary);
        if (slmResult.isPresent() && slmResult.get().confidence() >= SLM_CONFIDENCE_THRESHOLD) {
            log.info("SLM layer matched intent: {} (confidence: {}) for query: {}",
                    slmResult.get().intent(), slmResult.get().confidence(), query);
            return slmResult.get();
        }

        // 3. LLM 语义兜底与 OOD 域外拒识
        IntentClassificationResult fallbackResult = llmClassifier.classify(query, contextSummary);
        log.info("Fallback layer classified intent: {} (OOD: {}) for query: {}",
                fallbackResult.intent(), fallbackResult.isOutOfDomain(), query);
        return fallbackResult;
    }
}
