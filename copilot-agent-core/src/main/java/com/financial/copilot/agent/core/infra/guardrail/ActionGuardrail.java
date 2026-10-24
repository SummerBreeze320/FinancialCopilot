package com.financial.copilot.agent.core.infra.guardrail;

import com.financial.copilot.agent.core.infra.tool.HighRiskTool;
import com.financial.copilot.common.guardrail.GuardrailAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>动作侧护栏策略与死循环阻断器 (ActionGuardrail)</h1>
 * <p>在工具反射调用前评估权限白名单、高危人机协同审批 (ASK) 与重复死循环调用阻断 (DENY)。</p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class ActionGuardrail {

    private static final int MAX_IDENTICAL_CONSECUTIVE_CALLS = 2;

    private static final List<String> BLACKLISTED_PATTERNS = List.of(
            "shell", "exec", "eval", "system_", "rm_", "drop_database", "shutdown"
    );

    /** 记录每个任务的最近工具调用指纹列表，用于死循环检测 */
    private final Map<String, List<String>> callHistory = new ConcurrentHashMap<>();

    /**
     * 评估工具调用的护栏策略
     *
     * @param taskId 任务或会话标识
     * @param toolName 工具名称
     * @param arguments 调用入参
     * @param toolMethod 工具反射方法（可为 null）
     * @return 护栏决策动作 (ALLOW / ASK / DENY)
     */
    public GuardrailAction evaluateAction(String taskId,
                                          String toolName,
                                          Map<String, Object> arguments,
                                          Method toolMethod) {
        // 1. 黑名单检查
        String lowerToolName = toolName != null ? toolName.toLowerCase() : "";
        for (String pattern : BLACKLISTED_PATTERNS) {
            if (lowerToolName.contains(pattern)) {
                log.warn("ActionGuardrail DENIED blacklisted tool pattern '{}' in tool: {}", pattern, toolName);
                return GuardrailAction.DENY;
            }
        }

        // 2. 死循环检测 (Loop / Ping-pong Cycle Detection)
        String fingerprint = generateFingerprint(toolName, arguments);
        List<String> history = callHistory.computeIfAbsent(taskId, k -> Collections.synchronizedList(new ArrayList<>()));

        int repeatCount = 0;
        synchronized (history) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if (fingerprint.equals(history.get(i))) {
                    repeatCount++;
                } else {
                    break;
                }
            }
            history.add(fingerprint);
            // 维持历史队列大小上限
            if (history.size() > 50) {
                history.remove(0);
            }
        }

        if (repeatCount >= MAX_IDENTICAL_CONSECUTIVE_CALLS) {
            log.warn("ActionGuardrail DENIED: detected identical tool call cycle for task {}, tool {}, repeat count: {}",
                    taskId, toolName, repeatCount + 1);
            return GuardrailAction.DENY;
        }

        // 3. 高危工具检查 (Human-in-the-loop 审批介入)
        if (toolMethod != null && toolMethod.isAnnotationPresent(HighRiskTool.class)) {
            HighRiskTool annotation = toolMethod.getAnnotation(HighRiskTool.class);
            if (annotation.requiresApproval()) {
                log.info("ActionGuardrail ASK: tool {} marked with @HighRiskTool (category: {}, risk: {})",
                        toolName, annotation.category(), annotation.riskLevel());
                return GuardrailAction.ASK;
            }
        }

        return GuardrailAction.ALLOW;
    }

    private String generateFingerprint(String toolName, Map<String, Object> arguments) {
        String argsStr = arguments != null ? new TreeMap<>(arguments).toString() : "{}";
        return toolName + "::" + argsStr;
    }
}
