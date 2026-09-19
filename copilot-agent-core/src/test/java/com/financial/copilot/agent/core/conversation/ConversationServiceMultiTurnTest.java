package com.financial.copilot.agent.core.conversation;

import com.financial.copilot.agent.core.memory.ContextReducer;
import com.financial.copilot.agent.core.memory.ShortTermMemoryProperties;
import com.financial.copilot.agent.core.memory.ShortTermMemoryService;
import com.financial.copilot.agent.core.memory.store.local.InMemoryShortTermMemoryStore;
import com.financial.copilot.domain.conversation.entity.ConversationRun;
import com.financial.copilot.domain.conversation.model.MessageStatus;
import com.financial.copilot.domain.conversation.port.AgentToolAuditPort;
import com.financial.copilot.domain.conversation.port.ConversationPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationServiceMultiTurnTest {

    @Test
    @DisplayName("测试多轮连续调用 complete 时消息持续追加而非单轮覆盖")
    void testCompleteAppendsMessagesAcrossMultipleTurns() {
        ConversationPort port = Mockito.mock(ConversationPort.class);
        AgentToolAuditPort audits = Mockito.mock(AgentToolAuditPort.class);
        InMemoryShortTermMemoryStore store = new InMemoryShortTermMemoryStore();
        ShortTermMemoryService shortMemory = new ShortTermMemoryService(store, new ContextReducer(), new ShortTermMemoryProperties());
        ConversationPersistenceProperties props = new ConversationPersistenceProperties();
        props.setPersistenceEnabled(false);
        ApplicationEventPublisher publisher = Mockito.mock(ApplicationEventPublisher.class);

        ConversationService service = new ConversationService(port, audits, shortMemory, props, publisher, null);

        String sessionKey = "user1:conv1";
        ConversationRun run1 = new ConversationRun(UUID.randomUUID(), UUID.randomUUID(), null, null, MessageStatus.RUNNING);
        service.complete(1L, run1, sessionKey, "第一轮: 选两只消费基金", "第一轮研报: 易方达消费与汇添富消费", Map.of());

        List<String> turn1Context = shortMemory.getContext(sessionKey);
        assertThat(turn1Context).hasSize(2)
                .containsExactly("USER: 第一轮: 选两只消费基金", "ASSISTANT: 第一轮研报: 易方达消费与汇添富消费");

        // 第二轮追问
        ConversationRun run2 = new ConversationRun(UUID.randomUUID(), UUID.randomUUID(), null, null, MessageStatus.RUNNING);
        service.complete(1L, run2, sessionKey, "第二轮: 对比重仓白酒比例", "第二轮研报: 易方达持仓白酒45%，汇添富持仓38%", Map.of());

        List<String> turn2Context = shortMemory.getContext(sessionKey);
        // 验证两轮 4 条消息全部保留在短期记忆中
        assertThat(turn2Context).hasSize(4)
                .containsExactly(
                        "USER: 第一轮: 选两只消费基金",
                        "ASSISTANT: 第一轮研报: 易方达消费与汇添富消费",
                        "USER: 第二轮: 对比重仓白酒比例",
                        "ASSISTANT: 第二轮研报: 易方达持仓白酒45%，汇添富持仓38%"
                );
    }
}
