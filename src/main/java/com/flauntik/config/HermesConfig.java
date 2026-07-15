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
    private Map<String, OrgConfig> orgs;

    /**
     * Meta-app-level settings for the shared WhatsApp Cloud API webhook
     * ({@code /hermes/webhook/whatsapp}) - one Meta app's webhook can serve numbers
     * belonging to multiple orgs, so these aren't per-org.
     */
    private String whatsAppCloudApiVerifyToken;
    private String whatsAppCloudApiAppSecret;

    public OrgConfig getOrgConfig(String orgId) {
        OrgConfig orgConfig = orgs == null ? null : orgs.get(orgId);
        if (orgConfig == null) {
            throw new IllegalArgumentException("No org configured with id: " + orgId);
        }
        return orgConfig;
    }

    /**
     * Reverse lookup used by the shared Cloud API webhook to resolve which org a
     * message belongs to, since Meta identifies the destination number by
     * {@code phone_number_id} in the payload rather than a path segment.
     */
    public String findOrgIdByCloudApiPhoneNumberId(String phoneNumberId) {
        if (orgs == null || phoneNumberId == null) {
            return null;
        }
        return orgs.entrySet().stream()
                .filter(entry -> phoneNumberId.equals(entry.getValue().getCloudApiPhoneNumberId()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }
}
