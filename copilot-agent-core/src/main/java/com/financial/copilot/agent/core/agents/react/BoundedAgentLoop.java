package com.financial.copilot.agent.core.agents.react;

import java.util.*;
import java.util.function.Function;

/** Allowlisted local tool loop with a hard action budget. */
public class BoundedAgentLoop {
    private static final int MAX_ACTIONS = 5;
    private final Map<String, Function<Map<String, Object>, Object>> tools;

    public BoundedAgentLoop(Map<String, Function<Map<String, Object>, Object>> tools) {
        this.tools = tools == null ? Map.of() : Map.copyOf(tools);
    }

    public Object act(String tool, Map<String, Object> arguments) {
        Function<Map<String, Object>, Object> function = tools.get(tool);
        if (function == null) throw new IllegalArgumentException("Unregistered agent tool: " + tool);
        return function.apply(arguments == null ? Map.of() : arguments);
    }

    public Object run(Function<List<AgentObservation>, AgentAction> model) {
        List<AgentObservation> observations = new ArrayList<>();
        for (int i = 0; i < MAX_ACTIONS; i++) {
            AgentAction action = Objects.requireNonNull(model.apply(List.copyOf(observations)), "agent action");
            if (action.complete()) return action.result();
            Object result = act(action.tool(), action.arguments());
            observations.add(new AgentObservation(action.tool(), result));
        }
        return List.copyOf(observations);
    }
}
