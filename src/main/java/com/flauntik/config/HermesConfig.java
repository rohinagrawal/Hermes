package com.flauntik.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.vertx.core.DeploymentOptions;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HermesConfig {
    private String profile;
    private Integer port;
    private Map<String, DeploymentOptions> verticleDeploymentOptions;
    private Map<String, TenantConfig> tenants;

    /**
     * Meta-app-level settings for the shared WhatsApp Cloud API webhook
     * ({@code /hermes/webhook/whatsapp}) - one Meta app's webhook can serve numbers
     * belonging to multiple tenants, so these aren't per-tenant.
     */
    private String whatsAppCloudApiVerifyToken;
    private String whatsAppCloudApiAppSecret;

    /**
     * Conversation session cache tuning (see {@code service/session/SessionStore}).
     * Null = use defaults (5 min idle TTL, 10 000-session LRU cap).
     */
    private Integer sessionTtlMinutes;
    private Integer sessionMaxSize;

    /**
     * Optional persistent backing for conversation sessions. Null (absent from
     * config.json) - {@code HermesModule} binds {@code SessionStore} to the in-memory
     * cache exactly as before; present - binds to {@code JdbcSessionStore} instead.
     */
    private DatabaseConfig database;

    /**
     * Optional async ingestion + event publishing. Null (absent from config.json) -
     * real-provider webhooks stay fully synchronous and no flow-completion events are
     * published, exactly as before this was added.
     */
    private KafkaConfig kafka;

    public TenantConfig getTenantConfig(String tenantId) {
        TenantConfig tenantConfig = tenants == null ? null : tenants.get(tenantId);
        if (tenantConfig == null) {
            throw new IllegalArgumentException("No tenant configured with id: " + tenantId);
        }
        return tenantConfig;
    }

    /**
     * Reverse lookup used by the shared Cloud API webhook to resolve which tenant a
     * message belongs to, since Meta identifies the destination number by
     * {@code phone_number_id} in the payload rather than a path segment.
     */
    public String findTenantIdByCloudApiPhoneNumberId(String phoneNumberId) {
        if (tenants == null || phoneNumberId == null) {
            return null;
        }
        return tenants.entrySet().stream()
                .filter(entry -> phoneNumberId.equals(entry.getValue().getCloudApiPhoneNumberId()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }
}
