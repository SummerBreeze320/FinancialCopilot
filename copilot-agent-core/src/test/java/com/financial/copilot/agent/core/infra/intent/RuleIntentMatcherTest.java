package com.financial.copilot.agent.core.infra.intent;

import com.financial.copilot.common.intent.IntentClassificationResult;
import com.financial.copilot.common.intent.IntentType;
import com.financial.copilot.common.intent.MatchingLayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RuleIntentMatcher 规则意图极速匹配测试")
class RuleIntentMatcherTest {

    private final RuleIntentMatcher matcher = new RuleIntentMatcher();

    @Test
    @DisplayName("高频选基指令秒级命中 FUND_SCREENING 且提取板块槽位")
    void testMatchScreeningRule() {
        Optional<IntentClassificationResult> res = matcher.match("帮我初筛近1年收益率超过20%的消费基金");
        assertTrue(res.isPresent());
        assertEquals(IntentType.FUND_SCREENING, res.get().intent());
        assertEquals(MatchingLayer.RULE, res.get().matchedLayer());
        assertEquals(1.0, res.get().confidence());
        assertEquals("消费", res.get().slots().get("sector"));
    }

    @Test
    @DisplayName("对比指令命中 FUND_COMPARISON 并提取 6 位基金代码")
    void testMatchComparisonRule() {
        Optional<IntentClassificationResult> res = matcher.match("对比 005827 和 001508 谁的最大回撤控制更好");
        assertTrue(res.isPresent());
        assertEquals(IntentType.FUND_COMPARISON, res.get().intent());
        assertEquals(1.0, res.get().confidence());
        assertTrue(res.get().slots().toString().contains("005827"));
        assertTrue(res.get().slots().toString().contains("001508"));
    }

    @Test
    @DisplayName("非显式高频规则返回 empty 触发后续模型层")
    void testRuleMiss() {
        Optional<IntentClassificationResult> res = matcher.match("最近市场风格好像有所切换，你怎么看后市");
        assertTrue(res.isEmpty());
    }
}
