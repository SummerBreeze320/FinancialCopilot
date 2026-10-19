package com.financial.copilot.agent.core.business.agents.a2a.manager;

import com.financial.copilot.agent.core.business.agents.a2a.A2aAgentRegistry;
import com.financial.copilot.agent.core.business.agents.a2a.A2aExecutableAgent;
import com.financial.copilot.agent.core.business.agents.a2a.AgentCardDefinitions;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskEvent;
import com.financial.copilot.agent.core.business.agents.a2a.protocol.A2aTaskStatus;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("A2aTaskManager 任务管理器测试")
class A2aTaskManagerTest {

    @Test
    @DisplayName("验证创建任务、异步执行、状态流转与事件推送")
    void testCreateTaskAndStreamEvents() {
        A2aExecutableAgent mockAgent = mock(A2aExecutableAgent.class);
        when(mockAgent.agentCard()).thenReturn(AgentCardDefinitions.fundScreenerCard());
        Artifact<?> art = Artifact.of("art-1", ArtifactType.FUND_POOL, "node-1", "fund-result-data");
        doReturn(art).when(mockAgent).execute(any(), any(), any());

        A2aAgentRegistry registry = new A2aAgentRegistry(List.of(mockAgent));
        A2aTaskManager manager = new A2aTaskManager(registry);

        A2aTaskSession session = manager.createTask("FundScreenerAgent", Map.of("prompt", "消费基金"), 100L);
        assertNotNull(session);
        assertEquals(A2aTaskStatus.WORKING, session.getStatus());

        // Wait for task completion
        StepVerifier.create(manager.streamEvents(session.getTaskId()))
                .expectNextMatches(e -> "status_update".equals(e.type()))
                .expectNextMatches(e -> "artifact_update".equals(e.type()) && "fund-result-data".equals(e.payload()))
                .expectNextMatches(e -> "status_update".equals(e.type()) && A2aTaskStatus.COMPLETED.name().equals(e.payload()))
                .thenCancel()
                .verify();

        assertEquals(A2aTaskStatus.COMPLETED, session.getStatus());
    }

    @Test
    @DisplayName("验证取消正在执行的任务")
    void testCancelTask() {
        A2aExecutableAgent mockAgent = mock(A2aExecutableAgent.class);
        when(mockAgent.agentCard()).thenReturn(AgentCardDefinitions.fundScreenerCard());
        when(mockAgent.execute(any(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(500);
            return Artifact.of("art-1", ArtifactType.FUND_POOL, "node-1", "done");
        });

        A2aAgentRegistry registry = new A2aAgentRegistry(List.of(mockAgent));
        A2aTaskManager manager = new A2aTaskManager(registry);

        A2aTaskSession session = manager.createTask("FundScreenerAgent", Map.of("prompt", "长任务"), 100L);
        boolean cancelled = manager.cancelTask(session.getTaskId(), "用户主动取消");
        assertTrue(cancelled);
        assertEquals(A2aTaskStatus.CANCELLED, session.getStatus());
    }
}
