package com.flauntik.service.flow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.flauntik.pojo.FlowStep;
import com.flauntik.util.CommonUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads every tenant's flow.json from the classpath (works both from src/main/resources
 * in-IDE and from inside the packaged fat jar), validates them all up front, and fails
 * loudly with an aggregated report if any tenant's flow is broken -- rather than NPEing
 * at runtime for whichever user happens to hit the bad step.
 */
@Log4j2
@Singleton
public class TenantFlowRegistry {

    private static final String TENANTS_REGISTRY_RESOURCE = "flows/tenants-registry.json";
    private static final String TENANT_FLOW_RESOURCE_TEMPLATE = "flows/%s/flow.json";

    private final Map<String, Map<String, FlowStep>> orgFlows;

    @Inject
    public TenantFlowRegistry(FlowValidator flowValidator) {
        List<String> tenantIds = loadTenantIds();
        Map<String, Map<String, FlowStep>> loaded = new LinkedHashMap<>();
        List<String> allErrors = new ArrayList<>();

        for (String tenantId : tenantIds) {
            Map<String, FlowStep> flow = loadFlow(tenantId);
            loaded.put(tenantId, flow);
            allErrors.addAll(flowValidator.validate(tenantId, flow));
        }

        if (!allErrors.isEmpty()) {
            String report = String.join("\n  - ", allErrors);
            throw new IllegalStateException("Refusing to start: found " + allErrors.size()
                    + " flow configuration error(s):\n  - " + report);
        }

        this.orgFlows = Collections.unmodifiableMap(loaded);
        log.info("Loaded and validated {} tenant flow(s): {}", orgFlows.size(), orgFlows.keySet());
    }

    public Map<String, FlowStep> getFlow(String tenantId) {
        Map<String, FlowStep> flow = orgFlows.get(tenantId);
        if (flow == null) {
            throw new IllegalArgumentException("No workflow configured for tenant: " + tenantId);
        }
        return flow;
    }

    public java.util.Set<String> getTenantIds() {
        return orgFlows.keySet();
    }

    public int getStepCount(String tenantId) {
        return getFlow(tenantId).size();
    }

    private List<String> loadTenantIds() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(TENANTS_REGISTRY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + TENANTS_REGISTRY_RESOURCE);
            }
            return CommonUtil.mapper.readValue(in, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + TENANTS_REGISTRY_RESOURCE, e);
        }
    }

    private Map<String, FlowStep> loadFlow(String tenantId) {
        String resource = String.format(TENANT_FLOW_RESOURCE_TEMPLATE, tenantId);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + resource + " for tenant " + tenantId);
            }
            return CommonUtil.mapper.readValue(in, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Unable to parse flow for tenant " + tenantId, e);
        }
    }
}
