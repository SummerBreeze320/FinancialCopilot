package com.financial.copilot.agent.core.dag.artifact;

import java.util.Objects;

/**
 * <h1>多模态强类型投研产物契约 (Typed Artifact Contract)</h1>
 *
 * @param <T> 领域业务载荷类型
 */
public record Artifact<T>(
    String id,
    ArtifactType type,
    String producerNodeId,
    T payload,
    ArtifactMetadata metadata,
    EvidenceContract evidenceContract
) {
    public Artifact {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(producerNodeId, "producerNodeId cannot be null");
        metadata = metadata != null ? metadata : ArtifactMetadata.standard("SYSTEM");
        evidenceContract = evidenceContract != null ? evidenceContract : EvidenceContract.empty();
    }

    public static <T> Artifact<T> of(String id, ArtifactType type, String producerNodeId, T payload, ArtifactMetadata metadata) {
        return new Artifact<>(id, type, producerNodeId, payload, metadata, EvidenceContract.empty());
    }

    public static <T> Artifact<T> of(String id, ArtifactType type, String producerNodeId, T payload, ArtifactMetadata metadata, EvidenceContract contract) {
        return new Artifact<>(id, type, producerNodeId, payload, metadata, contract);
    }
}
