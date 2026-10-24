package com.financial.copilot.agent.core.infra.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.common.guardrail.GuardrailAction;
import com.financial.copilot.common.intent.IntentClassificationResult;
import com.financial.copilot.common.intent.IntentType;
import com.financial.copilot.common.intent.MatchingLayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("意图模型与约束载荷序列化测试")
class IntentModelSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("验证 IntentClassificationResult 正常与 OOD 结果序列化")
    void testSerialization() throws Exception {
        IntentClassificationResult result = new IntentClassificationResult(
                IntentType.FUND_SCREENING,
                0.95,
                Map.of("category", "EQUITY", "minReturn1Y", 0.20),
                MatchingLayer.RULE,
                false,
                null
        );

        String json = mapper.writeValueAsString(result);
        assertTrue(json.contains("FUND_SCREENING"));
        assertTrue(json.contains("RULE"));

        IntentClassificationResult read = mapper.readValue(json, IntentClassificationResult.class);
        assertEquals(IntentType.FUND_SCREENING, read.intent());
        assertEquals(0.95, read.confidence());
        assertEquals("EQUITY", read.slots().get("category"));

        IntentClassificationResult oodResult = new IntentClassificationResult(
                IntentType.OUT_OF_DOMAIN,
                0.99,
                Map.of(),
                MatchingLayer.LLM_FALLBACK,
                true,
                "我是公募投研助手，暂不支持生活类问答。"
        );
        assertTrue(oodResult.isOutOfDomain());
        assertNotNull(oodResult.clarificationPrompt());
    }

    @Test
    @DisplayName("验证 GuardrailAction 三级策略定义")
    void testGuardrailActionEnum() {
        assertEquals(GuardrailAction.ALLOW, GuardrailAction.valueOf("ALLOW"));
        assertEquals(GuardrailAction.ASK, GuardrailAction.valueOf("ASK"));
        assertEquals(GuardrailAction.DENY, GuardrailAction.valueOf("DENY"));
    }
}
