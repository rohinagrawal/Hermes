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

    public OrgConfig getOrgConfig(String orgId) {
        OrgConfig orgConfig = orgs == null ? null : orgs.get(orgId);
        if (orgConfig == null) {
            throw new IllegalArgumentException("No org configured with id: " + orgId);
        }
        return orgConfig;
    }
}
