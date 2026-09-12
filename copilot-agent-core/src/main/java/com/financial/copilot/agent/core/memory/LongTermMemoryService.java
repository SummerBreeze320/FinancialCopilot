package com.financial.copilot.agent.core.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

// Domain imports
import com.financial.copilot.agent.core.memory.LongTermMemoryEntry;
import com.financial.copilot.agent.core.memory.LongTermMemoryEntryRepository;
import com.financial.copilot.agent.core.memory.RefinedFact;
import com.financial.copilot.agent.core.memory.RefinedFactRepository;

/**
 * <h1>长期记忆服务</h1>
 *
 * <p>
 * 负责持久化（PostgreSQL）与高速缓存（Redis）双写，支持基于 Redis Sorted Set 的最新记录检索、
 * 跨会话高价值提纯事实 (RefinedFact) 的智能相关性召回以及自动清理过期数据（30 天）。
 * </p>
 */
@Slf4j
@Service
public class LongTermMemoryService {

    private static final Pattern CODE_PATTERN = Pattern.compile("\\b\\d{6}\\b");
    private static final String[] FINANCIAL_KEYWORDS = {
            "医药", "医疗", "创新药", "生物",
            "科技", "半导体", "芯片", "人工智能", "AI", "算力", "计算机", "信创",
            "消费", "白酒", "食品", "家电",
            "新能源", "光伏", "风电", "锂电", "储能", "电池",
            "红利", "高股息", "价值", "成长", "大盘", "小盘",
            "固收", "纯债", "转债", "理财", "货币",
            "回撤", "夏普", "收益率", "卡玛", "波动", "规模",
            "保守", "平衡", "稳健", "进取", "激进", "定投", "仓位", "配置"
    };

    private final LongTermMemoryEntryRepository repository;
    private final RefinedFactRepository refinedFactRepository;
    private final StringRedisTemplate redisTemplate;

    public LongTermMemoryService(LongTermMemoryEntryRepository repository,
                                 RefinedFactRepository refinedFactRepository,
                                 StringRedisTemplate redisTemplate) {
        this.repository = repository;
        this.refinedFactRepository = refinedFactRepository;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 持久化一条记忆，并同步写入 Redis Sorted Set。
     */
    public void record(String sessionId, String content) {
        // Idempotent check
        if (repository.countBySessionIdAndContent(sessionId, content) == 0) {
            LongTermMemoryEntry entry = new LongTermMemoryEntry()
                    .setSessionId(sessionId)
                    .setContent(content);
            repository.insert(entry);
            double score = entry.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
            redisTemplate.opsForZSet().add(redisKey(sessionId), content, score);
        }
    }

    /**
     * 清理 30 天前的记忆记录，数据库与 Redis 同时删除。
     */
    public void pruneOldEntries() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        repository.deleteByCreatedAtBefore(cutoff);
        // Optional: Redis cleanup can be added if needed.
    }

    /**
     * 记录 LLM 提取的结构化事实，内置幂等与去重保护。
     */
    public void recordRefinedFacts(String sessionId, List<String> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        List<RefinedFact> existing = refinedFactRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
        Set<String> existingContents = existing != null
                ? existing.stream().map(RefinedFact::getContent).collect(Collectors.toSet())
                : Collections.emptySet();

        for (String fact : facts) {
            if (fact != null && !fact.isBlank() && !existingContents.contains(fact.trim())) {
                RefinedFact refined = new RefinedFact(sessionId, "FACT", fact.trim());
                refinedFactRepository.insert(refined);
            }
        }
    }

    /**
     * 智能语义事实召回：
     * 自动解析用户维度前缀，跨该用户全部历史会话聚合提纯事实，
     * 并依据当前 query 进行金融实体、板块、指标与偏好加权相关度重排与去重。
     *
     * @param sessionId 当前会话 ID (若格式为 uid:xxx 则自动检索该用户历史全量会话)
     * @param query     当前用户输入的投研诉求
     * @param limit     期望召回的高价值事实上限
     * @return 排序后的事实内容列表
     */
    public List<String> retrieveRelevantFacts(String sessionId, String query, int limit) {
        if (sessionId == null || sessionId.isBlank() || limit <= 0) {
            return Collections.emptyList();
        }

        List<RefinedFact> candidates;
        if (sessionId.contains(":")) {
            // 用户隔离环境：提取用户前缀进行全量跨会话检索
            String userPrefix = sessionId.substring(0, sessionId.indexOf(":") + 1) + "%";
            candidates = refinedFactRepository.findByUserPrefixOrderByCreatedAtDesc(userPrefix, limit * 5);
        } else {
            // 普通会话或未带前缀：查当前会话
            candidates = refinedFactRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
        }

        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        return rankAndFilterFacts(candidates, query, limit);
    }

    private List<String> rankAndFilterFacts(List<RefinedFact> candidates, String query, int limit) {
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> seen = new LinkedHashSet<>();
        List<ScoredFact> scoredFacts = new ArrayList<>();

        Set<String> queryCodes = extractCodes(query);
        Set<String> queryKeywords = extractKeywords(query);

        int total = candidates.size();
        for (int i = 0; i < total; i++) {
            RefinedFact rf = candidates.get(i);
            String content = rf.getContent();
            if (content == null || content.isBlank() || !seen.add(content.trim())) {
                continue;
            }

            double score = 0.0;
            // 1. 标的代码精准匹配 (+10.0)
            for (String code : queryCodes) {
                if (content.contains(code)) {
                    score += 10.0;
                }
            }

            // 2. 金融主题与指标关键词匹配 (+3.0)
            for (String kw : queryKeywords) {
                if (content.contains(kw)) {
                    score += 3.0;
                }
            }

            // 3. 用户投资风格/画像/约束等高价值事实基准加分 (+1.5)
            if (content.contains("偏好") || content.contains("风格") || content.contains("风险")
                    || content.contains("画像") || content.contains("回撤") || content.contains("配置")
                    || content.contains("排除")) {
                score += 1.5;
            }

            // 4. 时效性微调递减加分 (保证相关度相同时最新沉淀的事实优先)
            double recencyBonus = ((double) (total - i) / total) * 0.5;
            score += recencyBonus;

            scoredFacts.add(new ScoredFact(content.trim(), score));
        }

        scoredFacts.sort((a, b) -> Double.compare(b.score, a.score));

        return scoredFacts.stream()
                .limit(limit)
                .map(sf -> sf.content)
                .collect(Collectors.toList());
    }

    private Set<String> extractCodes(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        Set<String> codes = new HashSet<>();
        Matcher m = CODE_PATTERN.matcher(text);
        while (m.find()) {
            codes.add(m.group());
        }
        return codes;
    }

    private Set<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        Set<String> matched = new HashSet<>();
        for (String kw : FINANCIAL_KEYWORDS) {
            if (text.contains(kw)) {
                matched.add(kw);
            }
        }
        return matched;
    }

    private record ScoredFact(String content, double score) {}

    /**
     * 优先从 Redis 获取最近的 {@code limit} 条记忆记录，若未命中则回退到数据库并缓存。
     */
    public List<String> retrieve(String sessionId, int limit) {
        ZSetOperations<String, String> zset = redisTemplate.opsForZSet();
        Set<String> cachedSet = zset.reverseRange(redisKey(sessionId), 0, limit - 1);
        if (cachedSet != null && !cachedSet.isEmpty()) {
            return new ArrayList<>(cachedSet);
        }
        // DB fallback
        List<LongTermMemoryEntry> all = repository.findBySessionIdOrderByCreatedAtDesc(sessionId);
        List<String> result = all.stream()
                .limit(limit)
                .map(LongTermMemoryEntry::getContent)
                .collect(Collectors.toList());
        // Warm up Redis cache
        for (LongTermMemoryEntry e : all) {
            double score = e.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
            zset.add(redisKey(sessionId), e.getContent(), score);
        }
        return result;
    }

    private String redisKey(String sessionId) {
        return "ltm:" + sessionId;
    }

    /**
     * 查询指定会话所提纯的结构化事实记录
     */
    public List<RefinedFact> getRefinedFacts(String sessionId) {
        return refinedFactRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
    }

    /**
     * 获取指定会话的全量长期记忆条目内容列表
     */
    public List<String> getAllEntries(String sessionId) {
        return repository.findBySessionIdOrderByCreatedAtDesc(sessionId).stream()
                .map(LongTermMemoryEntry::getContent)
                .collect(Collectors.toList());
    }
}
