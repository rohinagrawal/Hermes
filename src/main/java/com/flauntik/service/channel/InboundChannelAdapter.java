package com.flauntik.service.channel;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

/**
 * Parses a provider-specific raw webhook request into the same canonical shape
 * {@code IncomingMessageRequest} already expects ({@code tenantId}, {@code from},
 * {@code text.body}), so everything downstream of the HTTP layer stays provider-agnostic.
 */
public interface InboundChannelAdapter {
    JsonObject parseToCanonical(RoutingContext ctx);
}
