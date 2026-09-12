package com.financial.copilot.agent.core.agents;

import com.financial.copilot.agent.core.agents.react.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class BoundedAgentLoopTest {
    @Test
    void rejectsUnknownToolsAndStopsAtFiveActions() {
        AtomicInteger calls = new AtomicInteger();
        BoundedAgentLoop loop = new BoundedAgentLoop(Map.of("read", args -> "ok"));
        assertThatThrownBy(() -> loop.act("delete_database", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);

        loop.run(observations -> {
            calls.incrementAndGet();
            return AgentAction.call("read", Map.of());
        });

        assertThat(calls).hasValue(5);
    }
}
