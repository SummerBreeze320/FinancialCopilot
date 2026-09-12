package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.dag.artifact.Artifact;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/** Immutable, named input values resolved before a node starts. */
public final class NodeInput {
    private final Map<String, Object> values;
    private final Map<String, Artifact<?>> artifacts;

    public NodeInput(Map<String, Object> values) {
        this(values, Map.of());
    }

    public NodeInput(Map<String, Object> values, Map<String, Artifact<?>> artifacts) {
        this.values = values == null ? Map.of() : Map.copyOf(values);
        this.artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
    }

    public static NodeInput empty() {
        return new NodeInput(Map.of());
    }

    public Optional<Object> find(String name) {
        return Optional.ofNullable(values.get(name));
    }

    public <T> T require(String name, Class<T> type) {
        Object value = find(name).orElseThrow(() -> new NoSuchElementException("Missing node input: " + name));
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("Input " + name + " is " + value.getClass().getName() + ", expected " + type.getName());
        }
        return type.cast(value);
    }

    public Map<String, Object> asMap() {
        return values;
    }

    public Optional<Artifact<?>> artifact(String name) {
        return Optional.ofNullable(artifacts.get(name));
    }

    public Map<String, Artifact<?>> artifacts() {
        return artifacts;
    }
}
