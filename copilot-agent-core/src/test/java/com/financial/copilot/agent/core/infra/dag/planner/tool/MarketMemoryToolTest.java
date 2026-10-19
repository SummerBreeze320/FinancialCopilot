package com.financial.copilot.agent.core.infra.dag.planner.tool;

import com.financial.copilot.agent.core.infra.context.ExecutionIdentity;
import com.financial.copilot.agent.core.infra.memory.MemoryClient;
import com.financial.copilot.agent.core.infra.memory.MemoryScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketMemoryToolTest {

    @Test
    void memoryToolUsesInjectedIdentityAndOnlyAcceptsQuery() {
        MemoryClient memoryClient = mock(MemoryClient.class);
        MarketMemoryTool tool = new MarketMemoryTool(memoryClient);
        UUID conversationId = UUID.randomUUID();
        ExecutionIdentity identity = new ExecutionIdentity("DEFAULT", 7L, conversationId, "wf-1", "session");

        when(memoryClient.searchMemory(eq(identity), eq(MemoryScope.USER), eq("低回撤"), eq(5)))
                .thenReturn(List.of("fact1"));
        when(memoryClient.searchMemory(eq(identity), eq(MemoryScope.USER), contains("历史投资决策"), eq(5)))
                .thenReturn(List.of("decision1"));

        var result = tool.retrieveMemory(identity, "低回撤", 5);

        assertThat(result.relevantFacts()).containsExactly("fact1");
        assertThat(result.historicalDecisions()).containsExactly("decision1");
        verify(memoryClient).searchMemory(identity, MemoryScope.USER, "低回撤", 5);
        verify(memoryClient).searchMemory(eq(identity), eq(MemoryScope.USER), contains("历史投资决策"), eq(5));
    }
}
