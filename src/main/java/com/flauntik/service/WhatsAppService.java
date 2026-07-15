package com.flauntik.service;

import com.flauntik.config.HermesConfig;
import com.flauntik.enums.WhatsAppProviderType;
import com.flauntik.service.channel.OutboundMessageSender;
import com.google.inject.Inject;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.Map;

/**
 * Dispatches outbound WhatsApp sends to the right provider-specific
 * {@link OutboundMessageSender} for each org, based on {@code OrgConfig.provider}.
 */
public class WhatsAppService {

    private final HermesConfig hermesConfig;
    private final Map<WhatsAppProviderType, OutboundMessageSender> sendersByProvider;

    @Inject
    public WhatsAppService(HermesConfig hermesConfig, Map<WhatsAppProviderType, OutboundMessageSender> sendersByProvider) {
        this.hermesConfig = hermesConfig;
        this.sendersByProvider = sendersByProvider;
    }

    public Future<JsonObject> sendMessage(String orgId, String to, String message) {
        return senderFor(orgId).sendMessage(orgId, to, message);
    }

    public Future<JsonObject> sendMediaMessage(String orgId, String to, String mediaUrl, String caption) {
        return senderFor(orgId).sendMediaMessage(orgId, to, mediaUrl, caption);
    }

    public Future<JsonObject> sendTemplateMessage(String orgId, String to, String contentSid, Map<String, String> contentVariables) {
        return senderFor(orgId).sendTemplateMessage(orgId, to, contentSid, contentVariables);
    }

    private OutboundMessageSender senderFor(String orgId) {
        WhatsAppProviderType provider = hermesConfig.getOrgConfig(orgId).getProvider();
        OutboundMessageSender sender = sendersByProvider.get(provider);
        if (sender == null) {
            throw new IllegalStateException("No OutboundMessageSender bound for provider: " + provider);
        }
        return sender;
    }
}
