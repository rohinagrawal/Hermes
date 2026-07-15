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
 * Loads every org's flow.json from the classpath (works both from src/main/resources
 * in-IDE and from inside the packaged fat jar), validates them all up front, and fails
 * loudly with an aggregated report if any org's flow is broken -- rather than NPEing
 * at runtime for whichever user happens to hit the bad step.
 */
@Log4j2
@Singleton
public class OrgFlowRegistry {

    private static final String ORGS_REGISTRY_RESOURCE = "flows/orgs-registry.json";
    private static final String ORG_FLOW_RESOURCE_TEMPLATE = "flows/%s/flow.json";

    private final Map<String, Map<String, FlowStep>> orgFlows;

    @Inject
    public OrgFlowRegistry(FlowValidator flowValidator) {
        List<String> orgIds = loadOrgIds();
        Map<String, Map<String, FlowStep>> loaded = new LinkedHashMap<>();
        List<String> allErrors = new ArrayList<>();

        for (String orgId : orgIds) {
            Map<String, FlowStep> flow = loadFlow(orgId);
            loaded.put(orgId, flow);
            allErrors.addAll(flowValidator.validate(orgId, flow));
        }

        if (!allErrors.isEmpty()) {
            String report = String.join("\n  - ", allErrors);
            throw new IllegalStateException("Refusing to start: found " + allErrors.size()
                    + " flow configuration error(s):\n  - " + report);
        }

        this.orgFlows = Collections.unmodifiableMap(loaded);
        log.info("Loaded and validated {} org flow(s): {}", orgFlows.size(), orgFlows.keySet());
    }

    public Map<String, FlowStep> getFlow(String orgId) {
        Map<String, FlowStep> flow = orgFlows.get(orgId);
        if (flow == null) {
            throw new IllegalArgumentException("No workflow configured for org: " + orgId);
        }
        return flow;
    }

    public java.util.Set<String> getOrgIds() {
        return orgFlows.keySet();
    }

    public int getStepCount(String orgId) {
        return getFlow(orgId).size();
    }

    private List<String> loadOrgIds() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(ORGS_REGISTRY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + ORGS_REGISTRY_RESOURCE);
            }
            return CommonUtil.mapper.readValue(in, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + ORGS_REGISTRY_RESOURCE, e);
        }
    }

    private Map<String, FlowStep> loadFlow(String orgId) {
        String resource = String.format(ORG_FLOW_RESOURCE_TEMPLATE, orgId);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + resource + " for org " + orgId);
            }
            return CommonUtil.mapper.readValue(in, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Unable to parse flow for org " + orgId, e);
        }
    }
}
