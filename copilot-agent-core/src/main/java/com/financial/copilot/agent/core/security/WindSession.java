package com.financial.copilot.agent.core.security;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in‑memory store for wind session IDs.
 * Maps a userId to a UUID that is generated on login and used as a header
 * for all tool HTTP calls.
 */
public class WindSession {
    private static final ConcurrentHashMap<Long, String> SESSIONS = new ConcurrentHashMap<>();

    public static void put(Long userId, String windSessionId) {
        if (userId != null && windSessionId != null) {
            SESSIONS.put(userId, windSessionId);
        }
    }

    public static String get(Long userId) {
        return userId == null ? null : SESSIONS.get(userId);
    }

    public static void remove(Long userId) {
        if (userId != null) {
            SESSIONS.remove(userId);
        }
    }
}
