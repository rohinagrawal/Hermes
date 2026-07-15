package com.flauntik.service.session;

import com.flauntik.config.HermesConfig;
import com.flauntik.pojo.ConversationSession;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.log4j.Log4j2;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Per-user conversation sessions with a sliding TTL and an LRU size bound, backed by a
 * Guava {@link Cache}:
 *
 * <ul>
 *   <li><b>TTL</b> — {@code expireAfterAccess(ttl)}: a session is dropped after {@code ttl}
 *       minutes of inactivity. Every incoming message counts as access and resets the
 *       clock, so an active chat never expires mid-conversation; an abandoned one is
 *       cleaned up automatically.</li>
 *   <li><b>LRU</b> — {@code maximumSize(max)}: once the cache holds {@code max} sessions,
 *       Guava evicts the least-recently-used ones, bounding memory regardless of traffic.</li>
 * </ul>
 *
 * Both are configured via {@code HermesConfig.sessionTtlMinutes} / {@code sessionMaxSize}
 * (defaults: 5 minutes, 10 000 sessions). An evicted/expired user transparently restarts
 * at {@code start} on their next message — no error, no orphaned state.
 *
 * NOTE: still process-local (in-memory). This bounds and expires state on a single
 * instance; it is not shared across instances and does not survive a restart. A
 * Redis-backed implementation of the same {@code get}/{@code invalidate} surface is the
 * path to multi-instance durability.
 */
@Log4j2
@Singleton
public class SessionStore {

    private static final int DEFAULT_TTL_MINUTES = 5;
    private static final int DEFAULT_MAX_SIZE = 10_000;

    private final Cache<String, ConversationSession> sessions;

    @Inject
    public SessionStore(HermesConfig config) {
        int ttlMinutes = config.getSessionTtlMinutes() != null ? config.getSessionTtlMinutes() : DEFAULT_TTL_MINUTES;
        int maxSize = config.getSessionMaxSize() != null ? config.getSessionMaxSize() : DEFAULT_MAX_SIZE;

        this.sessions = CacheBuilder.newBuilder()
                .expireAfterAccess(ttlMinutes, TimeUnit.MINUTES)
                .maximumSize(maxSize)
                .build();

        log.info("Conversation SessionStore ready: {} min idle TTL, LRU cap {} sessions", ttlMinutes, maxSize);
    }

    /**
     * Returns the caller's live session, creating (and caching) a fresh one if none exists
     * or it has expired. Atomic per key, so two near-simultaneous messages from the same
     * new user can't each spawn a session and clobber the other.
     */
    public ConversationSession get(String orgId, String userId) {
        try {
            return sessions.get(key(orgId, userId), ConversationSession::new);
        } catch (ExecutionException e) {
            // ConversationSession::new can't actually throw; unwrap defensively.
            throw new IllegalStateException("Failed to load conversation session", e.getCause());
        }
    }

    /** Explicitly drops a session (e.g. on an operator "reset" or logout). */
    public void invalidate(String orgId, String userId) {
        sessions.invalidate(key(orgId, userId));
    }

    /** Current number of live sessions — handy for an admin/health view. */
    public long activeSessions() {
        return sessions.size();
    }

    private String key(String orgId, String userId) {
        return orgId + "|" + userId;
    }
}
