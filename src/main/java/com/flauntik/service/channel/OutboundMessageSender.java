package com.flauntik.service.channel;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.Map;

/**
 * Sends an outbound WhatsApp message through one specific provider. Implementations are
 * bound per {@code WhatsAppProviderType} in {@code HermesModule} and dispatched to by
 * {@code WhatsAppService} based on the target org's configured provider.
 */
public interface OutboundMessageSender {

    Future<JsonObject> sendMessage(String orgId, String to, String message);

    Future<JsonObject> sendMediaMessage(String orgId, String to, String mediaUrl, String caption);

    /**
     * Sends a pre-registered interactive template (Twilio Content Template SID, Meta
     * Cloud API template name, etc. - provider-specific) instead of plain text.
     */
    Future<JsonObject> sendTemplateMessage(String orgId, String to, String templateId, Map<String, String> templateVariables);
}
