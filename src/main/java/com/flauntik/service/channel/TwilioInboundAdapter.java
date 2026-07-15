package com.flauntik.service.channel;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

/**
 * Parses Twilio's WhatsApp webhook (form-urlencoded {@code From}/{@code Body} fields).
 * The org is already known from the {@code :orgId} path segment - Twilio lets each
 * WhatsApp number's webhook URL be configured per-org in the Twilio console, unlike
 * Meta's single app-level webhook.
 */
public class TwilioInboundAdapter implements InboundChannelAdapter {

    private static final String WHATSAPP_PREFIX = "whatsapp:";

    @Override
    public JsonObject parseToCanonical(RoutingContext ctx) {
        String orgId = ctx.pathParam("orgId");
        String from = ctx.request().getFormAttribute("From");
        String body = ctx.request().getFormAttribute("Body");
        if (from == null || body == null) {
            throw new IllegalArgumentException("Twilio webhook payload missing From/Body form fields");
        }

        return new JsonObject()
                .put("orgId", orgId)
                .put("from", stripWhatsAppPrefix(from))
                .put("text", new JsonObject().put("body", body));
    }

    private static String stripWhatsAppPrefix(String number) {
        return number.startsWith(WHATSAPP_PREFIX) ? number.substring(WHATSAPP_PREFIX.length()) : number;
    }
}
