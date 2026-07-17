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
 * {@link OutboundMessageSender} for each tenant, based on {@code TenantConfig.provider}.
 */
public class WhatsAppService {

    private final HermesConfig hermesConfig;
    private final Map<WhatsAppProviderType, OutboundMessageSender> sendersByProvider;

    @Inject
    public WhatsAppService(HermesConfig hermesConfig, Map<WhatsAppProviderType, OutboundMessageSender> sendersByProvider) {
        this.hermesConfig = hermesConfig;
        this.sendersByProvider = sendersByProvider;
    }

    public Future<JsonObject> sendMessage(String tenantId, String to, String message) {
        return senderFor(tenantId).sendMessage(tenantId, to, message);
    }

    public Future<JsonObject> sendMediaMessage(String tenantId, String to, String mediaUrl, String caption) {
        return senderFor(tenantId).sendMediaMessage(tenantId, to, mediaUrl, caption);
    }

    public Future<JsonObject> sendTemplateMessage(String tenantId, String to, String contentSid, Map<String, String> contentVariables) {
        return senderFor(tenantId).sendTemplateMessage(tenantId, to, contentSid, contentVariables);
    }

    private OutboundMessageSender senderFor(String tenantId) {
        WhatsAppProviderType provider = hermesConfig.getTenantConfig(tenantId).getProvider();
        OutboundMessageSender sender = sendersByProvider.get(provider);
        if (sender == null) {
            throw new IllegalStateException("No OutboundMessageSender bound for provider: " + provider);
        }
        return sender;
    }
}
