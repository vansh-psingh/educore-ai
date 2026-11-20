package com.educore.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-session conversation history for the universal AI chat.
 * Isolated by session id; capped to the most recent turns to control tokens.
 * Single-user/dev deployment - replace with persistent storage for multi-user.
 */
public final class ConversationStore {

    public record Turn(String role, String text) {} // role: "user" | "model"

    private static final int MAX_TURNS = 16; // ~8 exchanges
    private static final Map<String, Deque<Turn>> SESSIONS = new ConcurrentHashMap<>();

    private ConversationStore() {
    }

    public static List<Turn> history(String sessionId) {
        Deque<Turn> d = SESSIONS.get(sessionId);
        if (d == null) return List.of();
        synchronized (d) {
            return new ArrayList<>(d);
        }
    }

    public static void add(String sessionId, String role, String text) {
        if (sessionId == null || sessionId.isBlank()) return;
        Deque<Turn> d = SESSIONS.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        synchronized (d) {
            d.addLast(new Turn(role, text));
            while (d.size() > MAX_TURNS) {
                d.pollFirst();
            }
        }
    }

    public static void clear(String sessionId) {
        SESSIONS.remove(sessionId);
    }
}
