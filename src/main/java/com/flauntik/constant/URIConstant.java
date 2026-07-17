package com.flauntik.constant;

import com.google.common.collect.ImmutableSet;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;

import java.util.Set;

public interface URIConstant {
    Set<String> ALLOWED_HEADERS = ImmutableSet.of(
            HttpHeaders.ACCEPT.toString(),
            HttpHeaders.ACCEPT_ENCODING.toString(),
            HttpHeaders.ACCEPT_LANGUAGE.toString(),
            HttpHeaders.USER_AGENT.toString(),
            HttpHeaders.REFERER.toString(),
            HttpHeaders.CACHE_CONTROL.toString(),
            HttpHeaders.CONNECTION.toString(),
            HttpHeaders.CONTENT_TYPE.toString(),
            HttpHeaders.CONTENT_LENGTH.toString(),
            HttpHeaders.HOST.toString(),
            HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN.toString(),
            HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS.toString(),
            HttpHeaders.AUTHORIZATION.toString()
    );
    Set<HttpMethod> ALLOWED_METHODS = ImmutableSet.of(
            HttpMethod.GET,
            HttpMethod.POST,
            HttpMethod.OPTIONS,
            HttpMethod.DELETE,
            HttpMethod.PATCH,
            HttpMethod.PUT
    );

    /*URI*/
    String HEALTH_CHECK_API = "/healthcheck";
    String BASE_URI = "/hermes";
    String BASE_ADMIN_URI = BASE_URI + "/admin";

    String TEST = BASE_URI + "/test";
    String INCOMING_MESSAGE = BASE_URI + "/:tenantId/incoming_message";

    /** Per-tenant real provider webhook (Twilio: one webhook URL per WhatsApp number, configured per tenant). */
    String TENANT_WEBHOOK = BASE_URI + "/:tenantId/webhook";
    /** Shared provider-agnostic webhook (Meta WhatsApp Cloud API: one app-level webhook for all numbers/tenants). */
    String WHATSAPP_CLOUD_API_WEBHOOK = BASE_URI + "/webhook/whatsapp";

    String SET_LOGGING = BASE_ADMIN_URI + "/set_logging";
    String LIST_TENANTS = BASE_ADMIN_URI + "/tenants";

    /*Events*/
    String TEST_EVENT = "test";
    String SET_LOGGING_EVENT = "setLogging";
    String INCOMING_MESSAGE_EVENT = "incomingMessage";
    String LIST_TENANTS_EVENT = "listTenants";
}
