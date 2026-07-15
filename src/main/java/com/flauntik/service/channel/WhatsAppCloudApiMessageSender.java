package com.flauntik.service.channel;

import com.flauntik.config.HermesConfig;
import com.flauntik.config.OrgConfig;
import com.google.inject.Inject;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import lombok.extern.log4j.Log4j2;

import java.util.Map;

/**
 * Sends outbound WhatsApp messages via Meta's WhatsApp Cloud API (Graph API), using
 * each org's {@code cloudApiPhoneNumberId}/{@code cloudApiAccessToken}.
 *
 * NOTE: not validated against a live Meta sandbox (no real WhatsApp Business Account
 * available at implementation time) - the request shapes follow Meta's documented API,
 * but treat this as needing a real end-to-end check before production use. In
 * particular, {@code sendTemplateMessage}'s mapping of {@code contentVariables} to
 * template body parameters is positional (Meta templates take an ordered parameter
 * list, not named variables like Twilio) - the template must be registered with Meta
 * ahead of time and its placeholder order must match the caller's map iteration order.
 */
@Log4j2
public class WhatsAppCloudApiMessageSender implements OutboundMessageSender {

    private static final String GRAPH_API_URL = "https://graph.facebook.com/v20.0/%s/messages";

    private final WebClient webClient;
    private final HermesConfig hermesConfig;

    @Inject
    public WhatsAppCloudApiMessageSender(Vertx vertx, HermesConfig hermesConfig) {
        this.webClient = WebClient.create(vertx);
        this.hermesConfig = hermesConfig;
    }

    @Override
    public Future<JsonObject> sendMessage(String orgId, String to, String message) {
        JsonObject body = baseMessage(to, "text")
                .put("text", new JsonObject().put("body", message));
        return send(orgId, to, body);
    }

    @Override
    public Future<JsonObject> sendMediaMessage(String orgId, String to, String mediaUrl, String caption) {
        // FlowStep.mediaType is one of image/video/audio/document, matching Meta's message type field.
        JsonObject mediaObject = new JsonObject().put("link", mediaUrl);
        if (caption != null && !caption.isBlank()) {
            mediaObject.put("caption", caption);
        }
        JsonObject body = baseMessage(to, "image").put("image", mediaObject);
        return send(orgId, to, body);
    }

    @Override
    public Future<JsonObject> sendTemplateMessage(String orgId, String to, String templateName, Map<String, String> templateVariables) {
        JsonObject template = new JsonObject()
                .put("name", templateName)
                .put("language", new JsonObject().put("code", "en_US"));

        if (templateVariables != null && !templateVariables.isEmpty()) {
            JsonArray parameters = new JsonArray();
            templateVariables.values().forEach(value -> parameters.add(new JsonObject().put("type", "text").put("text", value)));
            template.put("components", new JsonArray().add(new JsonObject().put("type", "body").put("parameters", parameters)));
        }

        JsonObject body = baseMessage(to, "template").put("template", template);
        return send(orgId, to, body);
    }

    private static JsonObject baseMessage(String to, String type) {
        return new JsonObject()
                .put("messaging_product", "whatsapp")
                .put("to", to)
                .put("type", type);
    }

    private Future<JsonObject> send(String orgId, String to, JsonObject body) {
        OrgConfig orgConfig = hermesConfig.getOrgConfig(orgId);
        String url = String.format(GRAPH_API_URL, orgConfig.getCloudApiPhoneNumberId());

        return webClient.postAbs(url)
                .putHeader("Authorization", "Bearer " + orgConfig.getCloudApiAccessToken())
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(body)
                .map(response -> response.bodyAsJsonObject())
                .recover(err -> {
                    log.error("Failed to send WhatsApp message via Cloud API for org={} to={}", orgId, to, err);
                    return Future.failedFuture(err);
                });
    }
}
