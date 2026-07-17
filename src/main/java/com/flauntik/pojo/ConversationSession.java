package com.flauntik.pojo;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One user's live conversation state: where they are in the flow ({@code currentStepId},
 * null before their first message) and everything they've answered so far ({@code context},
 * keyed by step id, plus vars written by API_CALL/PAYMENT steps). Held in
 * {@code SessionStore}'s TTL + LRU cache — when a session is evicted (5 min idle or LRU
 * pressure) the user simply starts again at {@code start}.
 */
@Getter
public class ConversationSession {
    @Setter
    private volatile String currentStepId;
    private final Map<String, Object> context = new ConcurrentHashMap<>();
}
