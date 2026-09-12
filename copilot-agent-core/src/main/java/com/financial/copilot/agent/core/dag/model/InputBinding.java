package com.financial.copilot.agent.core.dag.model;

import com.financial.copilot.agent.core.dag.artifact.ArtifactType;

import java.util.Objects;

/** Explicitly binds one node input to one producer artifact. */
public record InputBinding(
        String name,
        String producerNodeId,
        ArtifactType expectedType,
        String path,
        boolean required
) {
    public InputBinding {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("binding name is required");
        if (producerNodeId == null || producerNodeId.isBlank()) throw new IllegalArgumentException("producerNodeId is required");
        Objects.requireNonNull(expectedType, "expectedType is required");
        path = path == null || path.isBlank() ? "$" : path;
        if (!path.equals("$") && !path.startsWith("$.")) {
            throw new IllegalArgumentException("binding path must be $ or start with $.: " + path);
        }
    }
}
