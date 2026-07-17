package com.flauntik.service.channel;

import com.flauntik.config.HermesConfig;
import com.flauntik.config.TenantConfig;
import com.google.inject.Inject;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import lombok.extern.log4j.Log4j2;

import java.util.Map;

/**
 * Sends outbound WhatsApp messages via Twilio's Messages API, using per-tenant credentials
 * (HermesConfig.tenants).
 */
@Log4j2
public class TwilioMessageSender implements OutboundMessageSender {

    private static final String TWILIO_API_URL = "https://api.twilio.com/2010-04-01/Accounts/%s/Messages.json";

    private final WebClient webClient;
    private final HermesConfig hermesConfig;
    // One breaker for the whole class: every tenant's Twilio calls hit the same
    // api.twilio.com host, so a shared breaker correctly reflects "is Twilio up right now".
    private final CircuitBreaker breaker;

    @Inject
    public TwilioMessageSender(Vertx vertx, HermesConfig hermesConfig) {
        this.webClient = WebClient.create(vertx);
        this.hermesConfig = hermesConfig;
        this.breaker = CircuitBreaker.create("twilio-send", vertx,
                new CircuitBreakerOptions().setMaxFailures(5).setTimeout(10_000).setResetTimeout(30_000).setFailuresRollingWindow(60_000));
    }

    @Override
    public Future<JsonObject> sendMessage(String tenantId, String to, String message) {
        return send(tenantId, to, new JsonObject().put("Body", message));
    }

    @Override
    public Future<JsonObject> sendMediaMessage(String tenantId, String to, String mediaUrl, String caption) {
        JsonObject form = new JsonObject().put("MediaUrl", mediaUrl);
        if (caption != null && !caption.isBlank()) {
            form.put("Body", caption);
        }
        return send(tenantId, to, form);
    }

    /**
     * Sends a pre-registered Twilio Content Template (the real mechanism for
     * WhatsApp interactive buttons/lists/cards) instead of a plain text Body.
     */
    @Override
    public Future<JsonObject> sendTemplateMessage(String tenantId, String to, String contentSid, Map<String, String> contentVariables) {
        JsonObject form = new JsonObject().put("ContentSid", contentSid);
        if (contentVariables != null && !contentVariables.isEmpty()) {
            form.put("ContentVariables", JsonObject.mapFrom(contentVariables).encode());
        }
        return send(tenantId, to, form);
    }

    private Future<JsonObject> send(String tenantId, String to, JsonObject formFields) {
        TenantConfig tenantConfig = hermesConfig.getTenantConfig(tenantId);
        JsonObject requestBody = formFields
                .put("To", "whatsapp:" + to)
                .put("From", tenantConfig.getTwilioFromWhatsAppNumber());

        String url = String.format(TWILIO_API_URL, tenantConfig.getTwilioAccountSid());
        String basicAuth = java.util.Base64.getEncoder().encodeToString(
                (tenantConfig.getTwilioAccountSid() + ":" + tenantConfig.getTwilioAuthToken()).getBytes());

        return breaker.<JsonObject>execute(promise -> {
                    Future<JsonObject> callFuture = webClient.postAbs(url)
                            .putHeader("Authorization", "Basic " + basicAuth)
                            .putHeader("Content-Type", "application/x-www-form-urlencoded")
                            .sendForm(jsonToMultiMap(requestBody))
                            .map(response -> response.bodyAsJsonObject());
                    callFuture.onComplete(promise);
                })
                .recover(err -> {
                    log.error("Failed to send WhatsApp message via Twilio for tenant={} to={}", tenantId, to, err);
                    return Future.failedFuture(err);
                });
    }

    private static MultiMap jsonToMultiMap(JsonObject jsonObject) {
        MultiMap form = MultiMap.caseInsensitiveMultiMap();
        jsonObject.forEach(entry -> form.add(entry.getKey(), entry.getValue().toString()));
        return form;
    }
}
