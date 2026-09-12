package com.financial.copilot.agent.core.dag.runtime;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Active run lookup keyed by owner and run id. */
public class GraphRunRegistry {
    private record RunKey(Long userId, String runId) {}
    private final ConcurrentHashMap<RunKey, GraphRunHandle> active = new ConcurrentHashMap<>();

    public GraphRunHandle register(Long userId, GraphRunHandle handle) {
        active.put(new RunKey(userId, handle.runId()), handle);
        handle.completion().whenComplete((result, error) -> active.remove(new RunKey(userId, handle.runId()), handle));
        return handle;
    }

    public Optional<GraphRunHandle> find(Long userId, String runId) {
        return Optional.ofNullable(active.get(new RunKey(userId, runId)));
    }

    public boolean cancel(Long userId, String runId, String reason) {
        GraphRunHandle handle = active.get(new RunKey(userId, runId));
        if (handle == null) return false;
        handle.cancel(reason);
        return true;
    }
}
