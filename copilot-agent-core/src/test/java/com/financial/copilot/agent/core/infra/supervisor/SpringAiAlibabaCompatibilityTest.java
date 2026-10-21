package com.financial.copilot.agent.core.infra.supervisor;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Spring AI Alibaba Agent Framework 关键 Supervisor 构建块在类路径中的存在性。
 */
class SpringAiAlibabaCompatibilityTest {

    @Test
    void exposesSupervisorBuildingBlocks() {
        assertThat(ReactAgent.class).isNotNull();
        assertThat(AgentTool.class).isNotNull();
        assertThat(new MemorySaver()).isNotNull();

        org.springframework.ai.chat.model.ChatModel mockModel = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        ReactAgent agent = ReactAgent.builder()
                .name("test_agent")
                .description("Test description")
                .model(mockModel)
                .build();
        assertThat(agent).isNotNull();

        for (var m : com.alibaba.cloud.ai.graph.RunnableConfig.builder().getClass().getMethods()) {
            System.out.println("RunnableConfig.Builder method: " + m.getName() + " -> " + java.util.Arrays.toString(m.getParameterTypes()));
        }
    }
}
