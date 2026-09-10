package com.financial.copilot.agent.core.pipeline;

import com.financial.copilot.common.dto.FundMetricsDTO;
import com.financial.copilot.domain.entity.FundInfo;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 复合投研黑板 (Research Blackboard)
 * 贯穿长链路流水线的共享状态与事实上下文总线
 */
public class ResearchBlackboard {

    public static final String KEY_CANDIDATE_FUNDS = "candidateFunds";
    public static final String KEY_CANDIDATE_METRICS = "candidateMetrics";
    public static final String KEY_MANAGER_RATINGS = "managerRatings";
    public static final String KEY_TOP_CANDIDATES = "topCandidates";
    public static final String KEY_COMPARISON_FACTS = "comparisonFacts";
    public static final String KEY_FINAL_REPORT = "finalReport";

    private final Map<String, Object> state = new ConcurrentHashMap<>();

    public void put(String key, Object value) {
        if (key != null && value != null) {
            state.put(key, value);
        }
    }

    public boolean has(String key) {
        return state.containsKey(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) {
        Object val = state.get(key);
        if (val == null) {
            return null;
        }
        if (clazz.isInstance(val)) {
            return clazz.cast(val);
        }
        return null;
    }

    public Object getRaw(String key) {
        return state.get(key);
    }

    @SuppressWarnings("unchecked")
    public List<FundInfo> getCandidateFunds() {
        Object val = state.get(KEY_CANDIDATE_FUNDS);
        if (val instanceof List<?> list) {
            return (List<FundInfo>) list;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public List<FundMetricsDTO> getCandidateMetrics() {
        Object val = state.get(KEY_CANDIDATE_METRICS);
        if (val instanceof List<?> list) {
            return (List<FundMetricsDTO>) list;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public List<String> getTopCandidates() {
        Object val = state.get(KEY_TOP_CANDIDATES);
        if (val instanceof List<?> list) {
            return (List<String>) list;
        }
        return Collections.emptyList();
    }

    public String getFinalReport() {
        return (String) state.get(KEY_FINAL_REPORT);
    }

    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(state);
    }
}
