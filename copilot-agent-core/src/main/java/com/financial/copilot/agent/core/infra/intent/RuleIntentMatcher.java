package com.financial.copilot.agent.core.infra.intent;

import com.financial.copilot.common.intent.IntentClassificationResult;
import com.financial.copilot.common.intent.IntentType;
import com.financial.copilot.common.intent.MatchingLayer;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h1>规则意图匹配器 (RuleIntentMatcher)</h1>
 * <p>基于关键词、高频动词与正则助记符实现 &lt; 5ms 零 Token 快速短路命中。</p>
 *
 * @author FinancialCopilot
 */
@Component
public class RuleIntentMatcher {

    private static final Pattern CODE_PATTERN = Pattern.compile("\\b(\\d{6})\\b");
    private static final List<String> SCREEN_KEYWORDS = List.of("选基", "初筛", "筛选", "挑一只", "推荐几只");
    private static final List<String> COMPARE_KEYWORDS = List.of("对比", "pk", "PK", "对标", "两强", "比一比");
    private static final List<String> ALLOC_KEYWORDS = List.of("资产配置", "大类资产", "仓位", "股债平衡");
    private static final List<String> REPORT_KEYWORDS = List.of("生成研报", "出具报告", "终审研报", "导出报告");
    private static final List<String> SECTOR_KEYWORDS = List.of("消费", "白酒", "半导体", "医药", "军工", "新能源", "科技", "中概");

    public Optional<IntentClassificationResult> match(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }

        String lower = query.trim().toLowerCase();
        Map<String, Object> slots = new HashMap<>();

        // 提取 6 位数字标的代码
        Matcher codeMatcher = CODE_PATTERN.matcher(query);
        List<String> codes = new ArrayList<>();
        while (codeMatcher.find()) {
            codes.add(codeMatcher.group(1));
        }
        if (!codes.isEmpty()) {
            slots.put("codes", codes);
        }

        // 提取板块词
        for (String sec : SECTOR_KEYWORDS) {
            if (query.contains(sec)) {
                slots.put("sector", sec);
                break;
            }
        }

        // 1. 对比意图
        if (COMPARE_KEYWORDS.stream().anyMatch(lower::contains)) {
            return Optional.of(new IntentClassificationResult(
                    IntentType.FUND_COMPARISON, 1.0, slots, MatchingLayer.RULE, false, null));
        }

        // 2. 初筛意图
        if (SCREEN_KEYWORDS.stream().anyMatch(lower::contains)) {
            return Optional.of(new IntentClassificationResult(
                    IntentType.FUND_SCREENING, 1.0, slots, MatchingLayer.RULE, false, null));
        }

        // 3. 资产配置意图
        if (ALLOC_KEYWORDS.stream().anyMatch(lower::contains)) {
            return Optional.of(new IntentClassificationResult(
                    IntentType.ASSET_ALLOCATION, 1.0, slots, MatchingLayer.RULE, false, null));
        }

        // 4. 研报合成意图
        if (REPORT_KEYWORDS.stream().anyMatch(lower::contains)) {
            return Optional.of(new IntentClassificationResult(
                    IntentType.REPORT_SYNTHESIS, 1.0, slots, MatchingLayer.RULE, false, null));
        }

        // 5. 单标的体检意图 (包含代码且带分析/体检/归因)
        if (codes.size() == 1 && (query.contains("体检") || query.contains("分析") || query.contains("归因") || query.contains("怎么样"))) {
            return Optional.of(new IntentClassificationResult(
                    IntentType.FUND_DIAGNOSIS, 1.0, slots, MatchingLayer.RULE, false, null));
        }

        return Optional.empty();
    }
}
