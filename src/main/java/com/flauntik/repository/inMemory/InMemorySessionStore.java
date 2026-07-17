package com.flauntik.repository.inMemory;

import com.flauntik.config.HermesConfig;
import com.flauntik.pojo.ConversationSession;
import com.flauntik.repository.SessionStore;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.vertx.core.Future;
import lombok.extern.log4j.Log4j2;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Default {@link SessionStore}: per-user conversation sessions with a sliding TTL and an
 * LRU size bound, backed by a Guava {@link Cache}:
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
 * instance; it is not shared across instances and does not survive a restart. Add a
 * {@code repository.redis.RedisSessionStore} implementing the same {@link SessionStore}
 * interface for multi-instance durability, and rebind it in {@code HermesModule}.
 */
@Log4j2
@Singleton
public class InMemorySessionStore implements SessionStore {

    private static final int DEFAULT_TTL_MINUTES = 5;
    private static final int DEFAULT_MAX_SIZE = 10_000;

    private final Cache<String, ConversationSession> sessions;

    @Inject
    public InMemorySessionStore(HermesConfig config) {
        int ttlMinutes = config.getSessionTtlMinutes() != null ? config.getSessionTtlMinutes() : DEFAULT_TTL_MINUTES;
        int maxSize = config.getSessionMaxSize() != null ? config.getSessionMaxSize() : DEFAULT_MAX_SIZE;

        this.sessions = CacheBuilder.newBuilder()
                .expireAfterAccess(ttlMinutes, TimeUnit.MINUTES)
                .maximumSize(maxSize)
                .build();

        log.info("Conversation SessionStore ready (in-memory): {} min idle TTL, LRU cap {} sessions", ttlMinutes, maxSize);
    }

    /**
     * Atomic per key, so two near-simultaneous messages from the same new user can't each
     * spawn a session and clobber the other.
     */
    @Override
    public ConversationSession get(String tenantId, String userId) {
        try {
            return sessions.get(key(tenantId, userId), ConversationSession::new);
        } catch (ExecutionException e) {
            // ConversationSession::new can't actually throw; unwrap defensively.
            throw new IllegalStateException("Failed to load conversation session", e.getCause());
        }
    }

    /**
     * No-op persist: {@link #get} already returns the live reference held by the cache, so
     * there is nothing to write back. Returns an already-completed future so the caller's
     * chain continues inline with no worker-thread hop.
     */
    @Override
    public Future<Void> save(String tenantId, String userId, ConversationSession session) {
        return Future.succeededFuture();
    }

    @Override
    public void invalidate(String tenantId, String userId) {
        sessions.invalidate(key(tenantId, userId));
    }

    @Override
    public long activeSessions() {
        return sessions.size();
    }

    private String key(String tenantId, String userId) {
        return tenantId + "|" + userId;
    }
}
