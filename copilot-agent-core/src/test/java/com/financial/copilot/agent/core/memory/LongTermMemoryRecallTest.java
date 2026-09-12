package com.financial.copilot.agent.core.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("长期记忆提纯事实跨会话召回与相关度排序测试")
class LongTermMemoryRecallTest {

    private LongTermMemoryEntryRepository entryRepository;
    private RefinedFactRepository refinedFactRepository;
    private StringRedisTemplate redisTemplate;
    private LongTermMemoryService memoryService;

    @BeforeEach
    void setUp() {
        entryRepository = mock(LongTermMemoryEntryRepository.class);
        refinedFactRepository = mock(RefinedFactRepository.class);
        redisTemplate = mock(StringRedisTemplate.class);
        memoryService = new LongTermMemoryService(entryRepository, refinedFactRepository, redisTemplate);
    }

    @Test
    @DisplayName("跨会话检索：同一用户前缀下的历史会话事实可被无缝召回")
    void testCrossSessionRetrievalByUserPrefix() {
        String currentSessionId = "1001:current-session-token";
        String expectedPrefix = "1001:%";

        List<RefinedFact> historicalFacts = List.of(
                createFact("1001:session-alpha", "用户偏好过去三年收益排名前列且最大回撤控制在20%以内的医药主题基金"),
                createFact("1001:session-beta", "000001 华夏成长被列为重点观察核心底仓"),
                createFact("1001:session-gamma", "用户风险等级为进取型，对短期波动容忍度较高")
        );

        when(refinedFactRepository.findByUserPrefixOrderByCreatedAtDesc(eq(expectedPrefix), anyInt()))
                .thenReturn(historicalFacts);

        List<String> recalled = memoryService.retrieveRelevantFacts(currentSessionId, "我想了解医药基金", 5);

        assertThat(recalled).isNotEmpty();
        assertThat(recalled).hasSize(3);
        // "医药" 关键词命中的事实应当排在首位
        assertThat(recalled.get(0)).contains("医药主题基金");
        verify(refinedFactRepository).findByUserPrefixOrderByCreatedAtDesc(eq(expectedPrefix), anyInt());
    }

    @Test
    @DisplayName("相关度加权排序：基金代码精准匹配具备最高优先级 (+10.0)")
    void testRelevanceRankingByCodeMatch() {
        String sessionId = "2002:session-active";

        List<RefinedFact> facts = List.of(
                createFact("2002:session-1", "用户关注科技半导体与人工智能产业链龙头标的"),
                createFact("2002:session-2", "005827 易方达蓝筹精选近三年夏普比率保持在同类前20%"),
                createFact("2002:session-3", "用户投资风格偏好大盘成长核心资产")
        );

        when(refinedFactRepository.findByUserPrefixOrderByCreatedAtDesc(eq("2002:%"), anyInt()))
                .thenReturn(facts);

        List<String> recalled = memoryService.retrieveRelevantFacts(sessionId, "请深度评测 005827 这只基金", 3);

        assertThat(recalled).isNotEmpty();
        // 包含代码 005827 的事实必须由于精准匹配高分置顶
        assertThat(recalled.get(0)).contains("005827");
    }

    @Test
    @DisplayName("事实去重与规范化：相同或空白事实自动过滤")
    void testDeduplicationAndNormalization() {
        String sessionId = "3003:session-test";

        List<RefinedFact> factsWithDuplicates = List.of(
                createFact("3003:session-1", "  用户偏好低波动与稳健收益  "),
                createFact("3003:session-2", "用户偏好低波动与稳健收益"),
                createFact("3003:session-3", "   "),
                createFact("3003:session-4", "排除近一年规模小于2亿的迷你基金")
        );

        when(refinedFactRepository.findByUserPrefixOrderByCreatedAtDesc(eq("3003:%"), anyInt()))
                .thenReturn(factsWithDuplicates);

        List<String> recalled = memoryService.retrieveRelevantFacts(sessionId, "选基标准", 5);

        assertThat(recalled).hasSize(2);
        assertThat(recalled).containsExactly(
                "用户偏好低波动与稳健收益",
                "排除近一年规模小于2亿的迷你基金"
        );
    }

    @Test
    @DisplayName("单会话或未带用户前缀会话回退单会话精准查询")
    void testAnonymousSessionFallback() {
        String sessionId = "standalone-anonymous-session";

        List<RefinedFact> sessionFacts = List.of(
                createFact(sessionId, "单会话临时记录：关注消费白酒板块反弹机会")
        );

        when(refinedFactRepository.findBySessionIdOrderByCreatedAtDesc(sessionId))
                .thenReturn(sessionFacts);

        List<String> recalled = memoryService.retrieveRelevantFacts(sessionId, "白酒消费基金", 5);

        assertThat(recalled).hasSize(1);
        assertThat(recalled.get(0)).contains("消费白酒");
        verify(refinedFactRepository).findBySessionIdOrderByCreatedAtDesc(sessionId);
        verify(refinedFactRepository, never()).findByUserPrefixOrderByCreatedAtDesc(anyString(), anyInt());
    }

    @Test
    @DisplayName("事实入库幂等性保护：重复事实不重复插入数据库")
    void testRecordRefinedFactsIdempotence() {
        String sessionId = "sess-idempotent-test";
        when(refinedFactRepository.findBySessionIdOrderByCreatedAtDesc(sessionId))
                .thenReturn(List.of(createFact(sessionId, "已知事实 A")));

        memoryService.recordRefinedFacts(sessionId, List.of("已知事实 A", "新发现事实 B"));

        // "已知事实 A" 已存在，只有 "新发现事实 B" 被插入
        verify(refinedFactRepository, times(1)).insert(any(RefinedFact.class));
    }

    private RefinedFact createFact(String sessionId, String content) {
        return new RefinedFact(sessionId, "FACT", content);
    }
}
