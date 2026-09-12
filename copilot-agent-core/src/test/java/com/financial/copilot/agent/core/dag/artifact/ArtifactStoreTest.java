package com.financial.copilot.agent.core.dag.artifact;

import com.financial.copilot.agent.core.dag.artifact.payload.FundPool;
import com.financial.copilot.agent.core.dag.artifact.payload.FundResearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactStoreTest {

    @Test
    @DisplayName("存入并强类型读取 Artifact<FundPool>")
    void testStoreAndGetTypedArtifact() {
        ArtifactStore store = new ArtifactStore();

        FundPool pool = new FundPool(List.of("001875", "161005"), "规模大于10亿且近三年夏普大于1.5", 2);
        ArtifactMetadata metadata = ArtifactMetadata.standard("Wind.API");
        EvidenceContract contract = new EvidenceContract(
                "初筛命中2只高夏普基金",
                List.of("artifact://db/screening"),
                List.of("规模基于最新季报"),
                List.of(),
                0.95
        );

        Artifact<FundPool> artifact = Artifact.of(
                "art_pool_1",
                ArtifactType.FUND_POOL,
                "node_screen",
                pool,
                metadata,
                contract
        );

        store.store("node_screen", artifact);

        Artifact<FundPool> retrieved = store.get("node_screen");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.payload().fundCodes()).containsExactly("001875", "161005");
        assertThat(retrieved.type()).isEqualTo(ArtifactType.FUND_POOL);
        assertThat(retrieved.evidenceContract().isSufficient()).isTrue();
    }

    @Test
    @DisplayName("获取指定上游节点集合的多源产物映射")
    void testGetAllUpstreamArtifacts() {
        ArtifactStore store = new ArtifactStore();

        FundPool pool = new FundPool(List.of("001875"), "测试初筛", 1);
        store.store("node_1", Artifact.of("art_1", ArtifactType.FUND_POOL, "node_1", pool, ArtifactMetadata.standard("DB")));

        FundResearchResult research = new FundResearchResult("001875", "张经理", 0.15, 1.8, 0.12, 45, Map.of("Alpha", 0.15), "业绩稳健");
        store.store("node_2", Artifact.of("art_2", ArtifactType.FUND_RESEARCH, "node_2", research, ArtifactMetadata.standard("Quant")));

        Map<String, Artifact<?>> upstreamMap = store.getAllUpstream(Set.of("node_1", "node_2", "non_existent"));
        assertThat(upstreamMap).hasSize(2);
        assertThat(upstreamMap).containsKey("node_1");
        assertThat(upstreamMap).containsKey("node_2");
    }

    @Test
    @DisplayName("验证 EvidenceContract 充分性校验与缺失证据项检测")
    void testEvidenceContractSufficiency() {
        // 1. 完备证据
        EvidenceContract completeContract = new EvidenceContract(
                "风格无漂移",
                List.of("uri:holdings_q1", "uri:holdings_q2"),
                List.of(),
                List.of(),
                0.85
        );
        assertThat(completeContract.isSufficient()).isTrue();

        // 2. 缺失关键证据项
        EvidenceContract missingEvidenceContract = new EvidenceContract(
                "风格推测",
                List.of("uri:holdings_q1"),
                List.of("假设重仓股无变动"),
                List.of("缺少2026Q2季报持仓明细"),
                0.70
        );
        assertThat(missingEvidenceContract.isSufficient()).isFalse();
        assertThat(missingEvidenceContract.missingEvidence()).contains("缺少2026Q2季报持仓明细");

        // 3. 置信度不足
        EvidenceContract lowConfidenceContract = new EvidenceContract(
                "低置信度推测",
                List.of(),
                List.of(),
                List.of(),
                0.50
        );
        assertThat(lowConfidenceContract.isSufficient()).isFalse();
    }

    @Test
    @DisplayName("按产物类型检索第一个匹配产物 findFirstByType")
    void testFindFirstByType() {
        ArtifactStore store = new ArtifactStore();

        FundPool pool = new FundPool(List.of("003095"), "医疗精选", 1);
        store.store("screen_node", Artifact.of("art_pool", ArtifactType.FUND_POOL, "screen_node", pool, ArtifactMetadata.standard("EastMoney")));

        Optional<Artifact<FundPool>> found = store.findFirstByType(ArtifactType.FUND_POOL);
        assertThat(found).isPresent();
        assertThat(found.get().payload().fundCodes()).contains("003095");

        Optional<Artifact<FundResearchResult>> notFound = store.findFirstByType(ArtifactType.FUND_RESEARCH);
        assertThat(notFound).isEmpty();
    }
}
