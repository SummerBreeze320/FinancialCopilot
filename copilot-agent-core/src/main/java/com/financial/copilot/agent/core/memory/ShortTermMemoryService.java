package com.financial.copilot.agent.core.memory;

import com.financial.copilot.agent.core.memory.store.ShortTermMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * <h1>短期记忆与会话上下文服务 (ShortTermMemoryService)</h1>
 * <p>
 * 统一管理单会话的多轮对话与短期交互记忆，代理存储至 {@link ShortTermMemoryStore}（Redis / 本地缓存），
 * 并由 {@link ContextReducer} 进行基于 Token 安全配额的智能滑动窗口滚动摘要治理。
 * </p>
 *
 * @author FinancialCopilot
 */
@Service
public class ShortTermMemoryService {
    private static final Logger log = LoggerFactory.getLogger(ShortTermMemoryService.class);

    private final ShortTermMemoryStore store;
    private final long ttlMinutes;
    private final int tokenThreshold;
    private final ContextReducer contextReducer;

    public ShortTermMemoryService(ShortTermMemoryStore store,
                                  ContextReducer contextReducer) {
        this(store, contextReducer, new ShortTermMemoryProperties());
    }

    @Autowired
    public ShortTermMemoryService(ShortTermMemoryStore store,
                                  ContextReducer contextReducer,
                                  @Autowired(required = false) ShortTermMemoryProperties properties) {
        this.store = store;
        this.contextReducer = contextReducer;
        this.ttlMinutes = properties != null ? properties.getTtlMinutes() : 30L;
        this.tokenThreshold = properties != null ? properties.getTokenThreshold() : 6000;
    }

    public void addMessage(String sessionId, String message) {
        String key = memoryKey(sessionId);
        store.append(key, message, Duration.ofMinutes(ttlMinutes));
        log.debug("[ShortTermMemory] Added message to {}", key);
    }

    public List<String> getContext(String sessionId) {
        return store.read(memoryKey(sessionId));
    }

    public List<String> getAllMessages(String sessionId) {
        return getContext(sessionId);
    }

    public void pruneIfNeeded(String sessionId) {
        List<String> context = getContext(sessionId);
        if (context == null || context.isEmpty()) {
            return;
        }
        int currentTokens = TokenUtil.estimateTokens(context);
        if (currentTokens > tokenThreshold) {
            log.info("[ShortTermMemory] Token budget exceeded ({} > {}), triggering rolling reduction", currentTokens, tokenThreshold);
            List<String> reduced = contextReducer.compressAndReduce(context, tokenThreshold);
            store.replace(memoryKey(sessionId), reduced, Duration.ofMinutes(ttlMinutes));
        }
    }

    public void removeEarliest(String sessionId) {
        store.removeEarliest(memoryKey(sessionId));
    }

    public void replaceContext(String sessionId, List<String> values) {
        String key = memoryKey(sessionId);
        store.replace(key, values, Duration.ofMinutes(ttlMinutes));
        if (values != null && !values.isEmpty()) {
            pruneIfNeeded(sessionId);
        }
    }

    public long getTtlMinutes() {
        return ttlMinutes;
    }

    public int getTokenThreshold() {
        return tokenThreshold;
    }

    private String memoryKey(String sessionId) {
        return "shortterm:session:" + sessionId;
    }
}
