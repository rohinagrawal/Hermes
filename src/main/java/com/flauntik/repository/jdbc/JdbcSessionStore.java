package com.flauntik.repository.jdbc;

import com.flauntik.config.DatabaseConfig;
import com.flauntik.config.HermesConfig;
import com.flauntik.pojo.ConversationSession;
import com.flauntik.repository.SessionStore;
import com.flauntik.util.CommonUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.extern.log4j.Log4j2;
import org.jdbi.v3.core.Jdbi;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * MySQL-backed {@link SessionStore}: a cache-aside layer over the same TTL+LRU cache
 * {@code InMemorySessionStore} uses (so the hot path is unchanged), with every session
 * also durably persisted to a {@code sessions} table via JDBI/HikariCP. Bound instead of
 * {@code InMemorySessionStore} only when {@code HermesConfig.database} is present - see
 * {@code HermesModule}.
 *
 * A cache miss falls through to a {@code SELECT}, so a restarted instance (or one that
 * never saw a given user before) recovers the conversation exactly where it left off
 * instead of restarting at {@code start}. This is the one behavior gap the in-memory
 * store can't close.
 */
@Log4j2
@Singleton
public class JdbcSessionStore implements SessionStore {

    private static final int DEFAULT_TTL_MINUTES = 5;
    private static final int DEFAULT_MAX_SIZE = 10_000;

    private final Cache<String, ConversationSession> cache;
    private final Jdbi jdbi;
    private final Vertx vertx;

    @Inject
    public JdbcSessionStore(Vertx vertx, HermesConfig config) {
        this.vertx = vertx;
        DatabaseConfig dbConfig = config.getDatabase();

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(dbConfig.getJdbcUrl());
        hikariConfig.setUsername(dbConfig.getUsername());
        hikariConfig.setPassword(dbConfig.getPassword());
        hikariConfig.setMaximumPoolSize(dbConfig.getMaximumPoolSize() != null ? dbConfig.getMaximumPoolSize() : 10);
        this.jdbi = Jdbi.create(new HikariDataSource(hikariConfig));
        ensureSchema();

        int ttlMinutes = config.getSessionTtlMinutes() != null ? config.getSessionTtlMinutes() : DEFAULT_TTL_MINUTES;
        int maxSize = config.getSessionMaxSize() != null ? config.getSessionMaxSize() : DEFAULT_MAX_SIZE;
        this.cache = CacheBuilder.newBuilder()
                .expireAfterAccess(ttlMinutes, TimeUnit.MINUTES)
                .maximumSize(maxSize)
                .build();

        log.info("Conversation SessionStore ready (MySQL-backed): {} min idle TTL, LRU cap {} sessions, jdbcUrl={}",
                ttlMinutes, maxSize, dbConfig.getJdbcUrl());
    }

    private void ensureSchema() {
        jdbi.useHandle(handle -> handle.execute(
                "CREATE TABLE IF NOT EXISTS sessions (" +
                        "tenant_id VARCHAR(128) NOT NULL, " +
                        "user_id VARCHAR(128) NOT NULL, " +
                        "current_step_id VARCHAR(128), " +
                        "context_json TEXT, " +
                        "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                        "PRIMARY KEY (tenant_id, user_id))"));
    }

    /**
     * Atomic per key (Guava {@code Cache.get(key, loader)}): concurrent first messages from
     * the same user can't each load/create a separate session and clobber the other - one
     * loads, the rest wait. Always invoked from a worker thread (via
     * {@code HermesService.handleIncomingMessage} inside {@code executeBlocking}), so the
     * cache-miss {@code SELECT} in the loader never runs on the event loop.
     */
    @Override
    public ConversationSession get(String tenantId, String userId) {
        try {
            return cache.get(key(tenantId, userId),
                    () -> loadFromDb(tenantId, userId).orElseGet(ConversationSession::new));
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to load conversation session", e.getCause());
        }
    }

    private Optional<ConversationSession> loadFromDb(String tenantId, String userId) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT current_step_id, context_json FROM sessions WHERE tenant_id = :tenantId AND user_id = :userId")
                .bind("tenantId", tenantId)
                .bind("userId", userId)
                .mapToMap()
                .findOne()
                .map(row -> {
                    ConversationSession session = new ConversationSession();
                    session.setCurrentStepId((String) row.get("current_step_id"));
                    String contextJson = (String) row.get("context_json");
                    if (contextJson != null) {
                        try {
                            Map<String, Object> context = CommonUtil.mapper.readValue(contextJson, Map.class);
                            session.getContext().putAll(context);
                        } catch (Exception e) {
                            log.warn("Failed to deserialize context_json for tenant={} user={}", tenantId, userId, e);
                        }
                    }
                    return session;
                }));
    }

    /**
     * The upsert (and JSON serialization) is blocking JDBC work, so it's dispatched to a
     * worker thread - the returned future completes when the write finishes. The caller's
     * completion chain may be on a Vert.x event-loop thread (e.g. right after an async
     * API_CALL step), and the event loop must never block on JDBC.
     */
    @Override
    public Future<Void> save(String tenantId, String userId, ConversationSession session) {
        return vertx.executeBlocking(promise -> {
            try {
                String contextJson = CommonUtil.mapper.writeValueAsString(session.getContext());
                jdbi.useHandle(handle -> handle.execute(
                        "INSERT INTO sessions (tenant_id, user_id, current_step_id, context_json) VALUES (?, ?, ?, ?) " +
                                "ON DUPLICATE KEY UPDATE current_step_id = VALUES(current_step_id), context_json = VALUES(context_json)",
                        tenantId, userId, session.getCurrentStepId(), contextJson));
                promise.complete();
            } catch (Exception e) {
                promise.fail(e);
            }
        }, false);
    }

    @Override
    public void invalidate(String tenantId, String userId) {
        cache.invalidate(key(tenantId, userId));
        jdbi.useHandle(handle -> handle.execute("DELETE FROM sessions WHERE tenant_id = ? AND user_id = ?", tenantId, userId));
    }

    @Override
    public long activeSessions() {
        return cache.size();
    }

    private String key(String tenantId, String userId) {
        return tenantId + "|" + userId;
    }
}
