package com.financial.copilot.agent.core.dag.artifact;

import java.time.Instant;
import java.util.List;

/**
 * <h1>产物审计与合规元数据 (Artifact Metadata)</h1>
 * 回答四个关键问题：结论来自什么数据？数据是否完整？置信度多少？由哪个 Agent/信源产生？
 */
public record ArtifactMetadata(
    Instant createdAt,             // 产生时间戳
    String schemaVersion,          // 契约结构版本号 (如 "1.0")
    List<String> evidenceIds,      // 支撑该结论的证据 ID 列表 (溯源到季报/公告/行情)
    Double confidence,             // 置信度打分 (0.0 ~ 1.0)
    boolean partial,               // 数据是否为部分降级结果 (true 表示数据不完整)
    String source                  // 物理信源渠道 (如 "Wind.API", "EastMoney.Crawler", "Internal.DB")
) {
    public static ArtifactMetadata standard(String source) {
        return new ArtifactMetadata(Instant.now(), "1.0", List.of(), 1.0, false, source);
    }

    public static ArtifactMetadata partial(String source, List<String> evidenceIds, String reason) {
        return new ArtifactMetadata(Instant.now(), "1.0", evidenceIds != null ? List.copyOf(evidenceIds) : List.of(), 0.7, true, source);
    }
}
