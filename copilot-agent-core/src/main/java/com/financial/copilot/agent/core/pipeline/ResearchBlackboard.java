package com.financial.copilot.agent.core.pipeline;

import com.financial.copilot.common.fund.dto.FundMetricsDTO;
import com.financial.copilot.domain.fund.entity.FundInfo;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>多智能体复合投研黑板 (Research Blackboard)</h1>
 * <p>
 * 作为跨阶段、跨 Agent 协作的状态数据总线，安全传递长链路流水线中的中间业务实体：
 * <ul>
 *   <li>初筛候选基金标的列表 (candidateFunds)</li>
 *   <li>量化指标计算集合 (candidateMetrics)</li>
 *   <li>多维经理能力评分矩阵 (managerRatings)</li>
 *   <li>决赛圈两强标的代码 (topCandidates)</li>
 *   <li>横向对标事实数据 (comparisonFacts)</li>
 *   <li>用户投资画像偏好 (userInvestmentProfile)</li>
 *   <li>最终合成投研研报 (finalReport)</li>
 * </ul>
 * 采用并发安全容器实现，支持并发 Fan-Out / Fan-In 评估模式。
 * </p>
 *
 * @author FinancialCopilot
 */
public class ResearchBlackboard {

    /** 候选基金标的池 Key */
    public static final String KEY_CANDIDATE_FUNDS = "candidateFunds";

    /** 候选标的量化指标集 Key */
    public static final String KEY_CANDIDATE_METRICS = "candidateMetrics";

    /** 经理多维体检评分矩阵 Key */
    public static final String KEY_MANAGER_RATINGS = "managerRatings";

    /** 决赛圈最优标的列表 Key */
    public static final String KEY_TOP_CANDIDATES = "topCandidates";

    /** 横向对标事实上下文 Key */
    public static final String KEY_COMPARISON_FACTS = "comparisonFacts";

    /** 用户投资画像偏好 Key */
    public static final String KEY_USER_INVESTMENT_PROFILE = "userInvestmentProfile";

    /** 最终投资建议研报 Key */
    public static final String KEY_FINAL_REPORT = "finalReport";

    /** 客户端是否开启深度思考推理模式 Key */
    public static final String KEY_ENABLE_THINKING = "enableThinking";

    private final Map<String, Object> state = new ConcurrentHashMap<>();

    /**
     * 向黑板写入中间事实数据
     *
     * @param key   数据键
     * @param value 数据值 (为 null 时不操作)
     */
    public void put(String key, Object value) {
        if (key != null && value != null) {
            state.put(key, value);
        }
    }

    /**
     * 判断黑板是否已包含指定键
     *
     * @param key 数据键
     * @return 存在返回 true，否则返回 false
     */
    public boolean has(String key) {
        return state.containsKey(key);
    }

    /**
     * 从黑板中安全读取指定类型的对象
     *
     * @param key   数据键
     * @param clazz 期望的目标类型
     * @param <T>   泛型参数
     * @return 类型匹配的数据，不存在或类型不符返回 null
     */
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

    /**
     * 获取未包装的原始对象
     *
     * @param key 数据键
     * @return 原始 Object
     */
    public Object getRaw(String key) {
        return state.get(key);
    }

    /**
     * 强类型读取初筛候选基金列表
     *
     * @return 基金信息列表
     */
    @SuppressWarnings("unchecked")
    public List<FundInfo> getCandidateFunds() {
        Object val = state.get(KEY_CANDIDATE_FUNDS);
        if (val instanceof List<?> list) {
            return (List<FundInfo>) list;
        }
        return Collections.emptyList();
    }

    /**
     * 强类型读取候选基金量化指标列表
     *
     * @return 量化指标 DTO 列表
     */
    @SuppressWarnings("unchecked")
    public List<FundMetricsDTO> getCandidateMetrics() {
        Object val = state.get(KEY_CANDIDATE_METRICS);
        if (val instanceof List<?> list) {
            return (List<FundMetricsDTO>) list;
        }
        return Collections.emptyList();
    }

    /**
     * 强类型读取选出的最优候选标的代码列表
     *
     * @return 代码字符串列表
     */
    @SuppressWarnings("unchecked")
    public List<String> getTopCandidates() {
        Object val = state.get(KEY_TOP_CANDIDATES);
        if (val instanceof List<?> list) {
            return (List<String>) list;
        }
        return Collections.emptyList();
    }

    /**
     * 获取当前研报目标用户的投资画像与风险偏好
     *
     * @return 用户投资偏好画像实体，未设置时返回 null
     */
    public UserInvestmentProfile getUserInvestmentProfile() {
        return get(KEY_USER_INVESTMENT_PROFILE, UserInvestmentProfile.class);
    }

    /**
     * 设置当前研报目标用户的投资画像与风险偏好
     *
     * @param profile 用户投资画像
     */
    public void setUserInvestmentProfile(UserInvestmentProfile profile) {
        if (profile != null) {
            state.put(KEY_USER_INVESTMENT_PROFILE, profile);
        }
    }

    /**
     * 获取最终合成的专业投资建议研报全文
     *
     * @return Markdown 格式研报文本
     */
    public String getFinalReport() {
        return (String) state.get(KEY_FINAL_REPORT);
    }

    /**
     * 获取当前是否开启深度思考推理模式
     *
     * @return true 若开启深度思考推理模式，false 极速标准投研模式
     */
    public boolean isEnableThinking() {
        Object val = state.get(KEY_ENABLE_THINKING);
        if (val instanceof Boolean b) {
            return b;
        }
        return false;
    }

    /**
     * 设置是否开启深度思考推理模式
     *
     * @param enableThinking 是否开启深度思考
     */
    public void setEnableThinking(Boolean enableThinking) {
        state.put(KEY_ENABLE_THINKING, Boolean.TRUE.equals(enableThinking));
    }

    /**
     * 获取当前黑板全局状态只读快照
     *
     * @return 不可变 Map 快照
     */
    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(state);
    }
}
