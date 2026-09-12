package com.financial.copilot.agent.core.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;

import java.util.List;
import java.util.Map;

/** Final reply plus the actual AgentScope tool observations produced by one ReAct call. */
public record AgentScopeInvocation(Msg reply, Map<String, List<ToolResultBlock>> observations) {
    public String requireLastText(String toolName) {
        List<ToolResultBlock> results = observations.get(toolName);
        if (results == null || results.isEmpty()) throw new IllegalStateException("Agent did not call required tool: " + toolName);
        ToolResultBlock result = results.get(results.size() - 1);
        if (result.getState() != ToolResultState.SUCCESS) throw new IllegalStateException("Tool failed: " + toolName);
        String text = result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", (left, right) -> left + right);
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
            return json.isTextual() ? json.asText() : text;
        } catch (Exception ignored) {
            return text;
        }
    }
}
