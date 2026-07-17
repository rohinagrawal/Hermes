package com.flauntik.repository;

import com.flauntik.pojo.ConversationSession;
import io.vertx.core.Future;

/**
 * Storage contract for per-user conversation state (current step + accumulated answers).
 * {@code FlowManager} depends only on this interface, never a concrete store, so a
 * different backing (e.g. Redis, for multi-instance durability) can be swapped in via one
 * Guice binding change in {@code HermesModule} without touching the flow engine.
 *
 * The default binding is {@code repository.inMemory.InMemorySessionStore}.
 */
public interface SessionStore {

    /**
     * Returns the caller's live session, creating one if none exists (or the previous one
     * expired/was evicted). Never returns null.
     */
    ConversationSession get(String tenantId, String userId);

    /**
     * Persists the session's current state. {@code FlowManager} calls this exactly once
     * per incoming message, after all automatic steps (API_CALL/PAYMENT/ACTION/BRANCH)
     * have cascaded through and the session has reached its next interactive step - not
     * once per step. A cache-backed store (the in-memory default) returns an already-
     * completed future since {@code get} already returned a live, mutable reference; a
     * database-backed store uses this as its explicit write point and returns a future
     * that completes when the (worker-thread-offloaded) write finishes.
     *
     * Returning a {@code Future} lets each implementation own its own threading: a
     * blocking DB write is dispatched to a worker so the caller's completion chain -
     * which may be running on a Vert.x event-loop thread after an async API_CALL step -
     * never blocks the event loop.
     */
    Future<Void> save(String tenantId, String userId, ConversationSession session);

    /** Explicitly drops a session (e.g. on an operator "reset" or logout). */
    void invalidate(String tenantId, String userId);

    /** Current number of live sessions — handy for an admin/health view. */
    long activeSessions();
}
