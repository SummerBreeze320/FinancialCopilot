package com.financial.copilot.agent.core.agents.react;

import java.util.Map;

public record AgentAction(String tool, Map<String, Object> arguments, boolean complete, Object result) {
    public static AgentAction call(String tool, Map<String, Object> arguments) {
        return new AgentAction(tool, arguments == null ? Map.of() : Map.copyOf(arguments), false, null);
    }
    public static AgentAction finish(Object result) {
        return new AgentAction(null, Map.of(), true, result);
    }
}
