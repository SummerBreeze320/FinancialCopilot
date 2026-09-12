package com.financial.copilot.agent.core.dag.runtime;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/** Immutable, named input values resolved before a node starts. */
public final class NodeInput {
    private final Map<String, Object> values;

    public NodeInput(Map<String, Object> values) {
        this.values = values == null ? Map.of() : Map.copyOf(values);
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
}
