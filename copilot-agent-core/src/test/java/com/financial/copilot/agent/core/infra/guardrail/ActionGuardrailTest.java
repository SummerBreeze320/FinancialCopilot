package com.financial.copilot.agent.core.infra.guardrail;

import com.financial.copilot.agent.core.infra.tool.HighRiskTool;
import com.financial.copilot.common.guardrail.GuardrailAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ActionGuardrail 动作侧护栏策略与死循环阻断测试")
class ActionGuardrailTest {

    private ActionGuardrail guardrail;

    public static class MockOperations {
        public String safeQuery() {
            return "OK";
        }

        @HighRiskTool(category = "ASSET_TRANSACTION", riskLevel = "HIGH", requiresApproval = true)
        public String rebalancePortfolio() {
            return "REBALANCED";
        }
    }

    @BeforeEach
    void setUp() {
        guardrail = new ActionGuardrail();
    }

    @Test
    @DisplayName("普通只读工具直接 ALLOW")
    void testSafeOperationAllows() throws NoSuchMethodException {
        Method method = MockOperations.class.getMethod("safeQuery");
        GuardrailAction action = guardrail.evaluateAction("task-1", "safe_query", Map.of("fundCode", "000001"), method);
        assertEquals(GuardrailAction.ALLOW, action);
    }

    @Test
    @DisplayName("标注 @HighRiskTool 工具决策为 ASK 人工介入挂起")
    void testHighRiskToolRequiresAsk() throws NoSuchMethodException {
        Method method = MockOperations.class.getMethod("rebalancePortfolio");
        GuardrailAction action = guardrail.evaluateAction("task-1", "rebalance_portfolio", Map.of("targetRatio", 0.3), method);
        assertEquals(GuardrailAction.ASK, action);
    }

    @Test
    @DisplayName("高频重复调用相同参数触发死循环阻断决策为 DENY")
    void testLoopDetectionDenies() throws NoSuchMethodException {
        Method method = MockOperations.class.getMethod("safeQuery");
        Map<String, Object> args = Map.of("fundCode", "000001");

        // 前两次正常通过
        assertEquals(GuardrailAction.ALLOW, guardrail.evaluateAction("task-loop", "safe_query", args, method));
        assertEquals(GuardrailAction.ALLOW, guardrail.evaluateAction("task-loop", "safe_query", args, method));

        // 第 3 次检测到相同工具与相同入参死循环，触发 DENY
        assertEquals(GuardrailAction.DENY, guardrail.evaluateAction("task-loop", "safe_query", args, method));
    }

    @Test
    @DisplayName("高危黑名单指令直接 DENY")
    void testBlacklistedToolDenies() {
        GuardrailAction action = guardrail.evaluateAction("task-1", "system_shell_exec", Map.of(), null);
        assertEquals(GuardrailAction.DENY, action);
    }
}
