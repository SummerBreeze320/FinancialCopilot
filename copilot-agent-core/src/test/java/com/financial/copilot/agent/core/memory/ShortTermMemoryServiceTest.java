package com.financial.copilot.agent.core.memory;

import com.financial.copilot.agent.core.memory.store.local.InMemoryShortTermMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermMemoryServiceTest {

    private InMemoryShortTermMemoryStore store;
    private ContextReducer reducer;
    private ShortTermMemoryProperties properties;
    private ShortTermMemoryService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryShortTermMemoryStore();
        reducer = new ContextReducer();
        properties = new ShortTermMemoryProperties();
        properties.setTtlMinutes(15L);
        properties.setTokenThreshold(250);
        service = new ShortTermMemoryService(store, reducer, properties);
    }

    @Test
    @DisplayName("测试消息追加与获取")
    void testAddAndGetMessages() {
        service.addMessage("session-1", "USER: 查易方达蓝筹");
        service.addMessage("session-1", "ASSISTANT: 易方达蓝筹近三年收益率为...");

        List<String> context = service.getContext("session-1");
        assertThat(context).hasSize(2)
                .containsExactly("USER: 查易方达蓝筹", "ASSISTANT: 易方达蓝筹近三年收益率为...");
    }

    @Test
    @DisplayName("测试超出配额时自动触发滚动摘要压缩")
    void testPruneWithRollingSummary() {
        String sessionId = "session-overflow";
        // 持续添加长文本迫使其超出 250 Token 阈值
        for (int i = 1; i <= 6; i++) {
            service.addMessage(sessionId, "USER: 轮次" + i + " 详细提问公募基金投资方案与行业配置组合分析");
            service.addMessage(sessionId, "ASSISTANT: 轮次" + i + " 输出研报报告建议关注高股息红利低波资产及成长科技两头配置策略");
        }

        service.pruneIfNeeded(sessionId);

        List<String> context = service.getContext(sessionId);
        assertThat(context).isNotEmpty();
        // 首条为提纯滚动摘要
        assertThat(context.get(0)).startsWith("【前期历史交互摘要】");
        // 最新轮次保持在尾部
        assertThat(context.get(context.size() - 1)).contains("轮次6");
    }

    @Test
    @DisplayName("测试属性正确暴露")
    void testPropertiesExposed() {
        assertThat(service.getTtlMinutes()).isEqualTo(15L);
        assertThat(service.getTokenThreshold()).isEqualTo(250);
    }
}
