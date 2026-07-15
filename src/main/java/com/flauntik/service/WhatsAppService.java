package com.flauntik.service;

import com.flauntik.config.HermesConfig;
import com.flauntik.config.OrgConfig;
import com.google.inject.Inject;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import lombok.extern.log4j.Log4j2;

import java.util.Map;

/**
 * Sends outbound WhatsApp messages via Twilio, using per-org credentials
 * (HermesConfig.orgs) instead of a single hardcoded account.
 */
@Log4j2
public class WhatsAppService {

    private static final String TWILIO_API_URL = "https://api.twilio.com/2010-04-01/Accounts/%s/Messages.json";

    private final WebClient webClient;
    private final HermesConfig hermesConfig;

    @Inject
    public WhatsAppService(Vertx vertx, HermesConfig hermesConfig) {
        this.webClient = WebClient.create(vertx);
        this.hermesConfig = hermesConfig;
    }

    public Future<JsonObject> sendMessage(String orgId, String to, String message) {
        return send(orgId, to, new JsonObject().put("Body", message));
    }

    public Future<JsonObject> sendMediaMessage(String orgId, String to, String mediaUrl, String caption) {
        JsonObject form = new JsonObject().put("MediaUrl", mediaUrl);
        if (caption != null && !caption.isBlank()) {
            form.put("Body", caption);
        }
        return send(orgId, to, form);
    }

    /**
     * Sends a pre-registered Twilio Content Template (the real mechanism for
     * WhatsApp interactive buttons/lists/cards) instead of a plain text Body.
     */
    public Future<JsonObject> sendTemplateMessage(String orgId, String to, String contentSid, Map<String, String> contentVariables) {
        JsonObject form = new JsonObject().put("ContentSid", contentSid);
        if (contentVariables != null && !contentVariables.isEmpty()) {
            form.put("ContentVariables", JsonObject.mapFrom(contentVariables).encode());
        }
        return send(orgId, to, form);
    }

    private Future<JsonObject> send(String orgId, String to, JsonObject formFields) {
        OrgConfig orgConfig = hermesConfig.getOrgConfig(orgId);
        JsonObject requestBody = formFields
                .put("To", "whatsapp:" + to)
                .put("From", orgConfig.getTwilioFromWhatsAppNumber());

        String url = String.format(TWILIO_API_URL, orgConfig.getTwilioAccountSid());
        String basicAuth = java.util.Base64.getEncoder().encodeToString(
                (orgConfig.getTwilioAccountSid() + ":" + orgConfig.getTwilioAuthToken()).getBytes());

        return webClient.postAbs(url)
                .putHeader("Authorization", "Basic " + basicAuth)
                .putHeader("Content-Type", "application/x-www-form-urlencoded")
                .sendForm(jsonToMultiMap(requestBody))
                .map(response -> response.bodyAsJsonObject())
                .recover(err -> {
                    log.error("Failed to send WhatsApp message via Twilio for org={} to={}", orgId, to, err);
                    return Future.failedFuture(err);
                });
    }

    private static MultiMap jsonToMultiMap(JsonObject jsonObject) {
        MultiMap form = MultiMap.caseInsensitiveMultiMap();
        jsonObject.forEach(entry -> form.add(entry.getKey(), entry.getValue().toString()));
        return form;
    }
}
