package com.flauntik.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared Jackson {@link ObjectMapper}, configured once and reused for flow.json parsing
 * (TenantFlowRegistry) and API_CALL response parsing (ApiCallService).
 */
public class CommonUtil {

    public static ObjectMapper mapper = null;

    static {
        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

}
