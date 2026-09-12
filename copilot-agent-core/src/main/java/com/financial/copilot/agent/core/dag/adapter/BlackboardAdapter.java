package com.financial.copilot.agent.core.dag.adapter;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import com.financial.copilot.agent.core.dag.artifact.ArtifactMetadata;
import com.financial.copilot.agent.core.dag.artifact.ArtifactStore;
import com.financial.copilot.agent.core.dag.artifact.ArtifactType;
import com.financial.copilot.agent.core.dag.artifact.EvidenceContract;
import com.financial.copilot.agent.core.pipeline.ResearchBlackboard;
import com.financial.copilot.agent.core.dag.artifact.payload.ComparisonReport;
import com.financial.copilot.agent.core.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.dag.artifact.payload.FundPool;
import com.financial.copilot.agent.core.dag.artifact.payload.FundResearchResult;
import com.financial.copilot.domain.fund.entity.FundInfo;
import com.financial.copilot.domain.user.entity.UserInvestmentProfile;

import java.util.*;

/**
 * <h1>黑板与强类型产物总线双向桥接适配器 (BlackboardAdapter)</h1>
 * <p>
 * 负责在旧版全局共享的 {@link ResearchBlackboard} 与新版强类型、版本化的 {@link ArtifactStore} 之间
 * 提供无损双向转换、状态同步与全局上下文隔离传递。
 * </p>
 */
public class BlackboardAdapter {

    /**
     * 将全局控制参数与上下文从 Blackboard 复制到 ArtifactStore 全局上下文
     */
    public static void copyGlobalContext(ResearchBlackboard blackboard, ArtifactStore store) {
        if (blackboard == null || store == null) return;

        if (blackboard.getUserInvestmentProfile() != null) {
            store.putGlobalContext(ResearchBlackboard.KEY_USER_INVESTMENT_PROFILE, blackboard.getUserInvestmentProfile());
        }
        store.putGlobalContext(ResearchBlackboard.KEY_ENABLE_THINKING, blackboard.isEnableThinking());

        if (blackboard.getRaw("usageConsumer") != null) {
            store.putGlobalContext("usageConsumer", blackboard.getRaw("usageConsumer"));
        }
        if (blackboard.getRaw("userPrompt") != null) {
            store.putGlobalContext("userPrompt", blackboard.getRaw("userPrompt"));
        }
        if (blackboard.getRaw("sessionId") != null) {
            store.putGlobalContext("sessionId", blackboard.getRaw("sessionId"));
        }
    }

    /**
     * 将全局控制参数与上下文从 ArtifactStore 复制回 Blackboard
     */
    public static void copyGlobalContext(ArtifactStore store, ResearchBlackboard blackboard) {
        if (store == null || blackboard == null) return;

        Object profileObj = store.getGlobalContext(ResearchBlackboard.KEY_USER_INVESTMENT_PROFILE);
        if (profileObj instanceof UserInvestmentProfile profile) {
            blackboard.setUserInvestmentProfile(profile);
        }

        Object enableThinkingObj = store.getGlobalContext(ResearchBlackboard.KEY_ENABLE_THINKING);
        if (enableThinkingObj instanceof Boolean b) {
            blackboard.setEnableThinking(b);
        }

        if (store.getGlobalContext("usageConsumer") != null) {
            blackboard.put("usageConsumer", store.getGlobalContext("usageConsumer"));
        }
        if (store.getGlobalContext("userPrompt") != null) {
            blackboard.put("userPrompt", store.getGlobalContext("userPrompt"));
        }
        if (store.getGlobalContext("sessionId") != null) {
            blackboard.put("sessionId", store.getGlobalContext("sessionId"));
        }
    }

    /**
     * 根据节点任务类型与 Blackboard 当前产出，提取强类型 Artifact
     */
    public static Artifact<?> extractArtifactFromBlackboard(String nodeId, String taskType, ResearchBlackboard blackboard) {
        String artifactId = "art-" + UUID.randomUUID().toString().substring(0, 8);
        ArtifactMetadata metadata = ArtifactMetadata.standard(nodeId);

        if (taskType == null) {
            return new Artifact<>(artifactId, ArtifactType.GENERAL, nodeId, Map.of(), metadata, EvidenceContract.empty());
        }

        return switch (taskType.toUpperCase()) {
            case "SCREENING" -> {
                List<FundInfo> candidates = blackboard.getCandidateFunds();
                yield new Artifact<>(
                        artifactId,
                        ArtifactType.FUND_POOL,
                        nodeId,
                        candidates != null ? candidates : List.of(),
                        metadata,
                        EvidenceContract.empty()
                );
            }
            case "BATCH_ANALYSIS" -> {
                List<?> ratings = blackboard.get(ResearchBlackboard.KEY_MANAGER_RATINGS, List.class);
                List<String> topCandidates = blackboard.getTopCandidates();
                Map<String, Object> payload = new HashMap<>();
                if (ratings != null) payload.put("ratings", ratings);
                if (topCandidates != null) payload.put("topCandidates", topCandidates);
                yield new Artifact<>(
                        artifactId,
                        ArtifactType.FUND_RESEARCH,
                        nodeId,
                        payload,
                        metadata,
                        EvidenceContract.empty()
                );
            }
            case "COMPARISON" -> {
                String facts = (String) blackboard.getRaw(ResearchBlackboard.KEY_COMPARISON_FACTS);
                yield new Artifact<>(
                        artifactId,
                        ArtifactType.COMPARISON_REPORT,
                        nodeId,
                        facts != null ? facts : "",
                        metadata,
                        EvidenceContract.empty()
                );
            }
            case "SYNTHESIS" -> {
                String report = blackboard.getFinalReport();
                yield new Artifact<>(
                        artifactId,
                        ArtifactType.FINAL_REPORT,
                        nodeId,
                        report != null ? report : "",
                        metadata,
                        EvidenceContract.empty()
                );
            }
            default -> {
                Object raw = blackboard.getRaw(nodeId);
                yield new Artifact<>(
                        artifactId,
                        ArtifactType.GENERAL,
                        nodeId,
                        raw != null ? raw : Map.of(),
                        metadata,
                        EvidenceContract.empty()
                );
            }
        };
    }

    /**
     * 将单个 Artifact 产物安全映射回 Blackboard 对应状态槽位
     */
    @SuppressWarnings("unchecked")
    public static void applyArtifactToBlackboard(Artifact<?> artifact, ResearchBlackboard blackboard) {
        if (artifact == null || blackboard == null) return;

        switch (artifact.type()) {
            case FUND_POOL -> {
                if (artifact.payload() instanceof List<?> list) {
                    blackboard.put(ResearchBlackboard.KEY_CANDIDATE_FUNDS, list);
                } else if (artifact.payload() instanceof FundPool pool) {
                    if (pool.funds() != null && !pool.funds().isEmpty()) {
                        blackboard.put(ResearchBlackboard.KEY_CANDIDATE_FUNDS, pool.funds());
                    }
                }
            }
            case FUND_RESEARCH -> {
                if (artifact.payload() instanceof Map<?, ?> map) {
                    if (map.containsKey("ratings")) {
                        blackboard.put(ResearchBlackboard.KEY_MANAGER_RATINGS, map.get("ratings"));
                    }
                    if (map.containsKey("topCandidates")) {
                        blackboard.put(ResearchBlackboard.KEY_TOP_CANDIDATES, map.get("topCandidates"));
                    }
                } else if (artifact.payload() instanceof FundResearchResult res) {
                    if (res.evaluatedFunds() != null && !res.evaluatedFunds().isEmpty()) {
                        blackboard.put(ResearchBlackboard.KEY_MANAGER_RATINGS, res.evaluatedFunds());
                    }
                    if (res.topCandidates() != null && !res.topCandidates().isEmpty()) {
                        blackboard.put(ResearchBlackboard.KEY_TOP_CANDIDATES, res.topCandidates());
                    }
                }
            }
            case COMPARISON_REPORT -> {
                if (artifact.payload() instanceof ComparisonReport comp) {
                    blackboard.put(ResearchBlackboard.KEY_COMPARISON_FACTS, comp.comparisonAnalysis());
                } else if (artifact.payload() != null) {
                    blackboard.put(ResearchBlackboard.KEY_COMPARISON_FACTS, artifact.payload().toString());
                }
            }
            case FINAL_REPORT -> {
                if (artifact.payload() instanceof FinalSynthesisReport rep) {
                    blackboard.put(ResearchBlackboard.KEY_FINAL_REPORT, rep.markdownReport());
                } else if (artifact.payload() != null) {
                    blackboard.put(ResearchBlackboard.KEY_FINAL_REPORT, artifact.payload().toString());
                }
            }
            case GENERAL -> {
                if (artifact.payload() != null) {
                    blackboard.put(artifact.producerNodeId(), artifact.payload());
                }
            }
            default -> {}
        }
    }

    /**
     * 将 ArtifactStore 中的全部产物与全局上下文同步至 Blackboard
     */
    public static void syncStoreToBlackboard(ArtifactStore store, ResearchBlackboard blackboard) {
        if (store == null || blackboard == null) return;
        copyGlobalContext(store, blackboard);
        for (Artifact<?> artifact : store.getAllArtifacts().values()) {
            applyArtifactToBlackboard(artifact, blackboard);
        }
    }
}
