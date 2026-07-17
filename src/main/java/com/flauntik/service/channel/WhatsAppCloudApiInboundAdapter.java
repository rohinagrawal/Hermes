package com.flauntik.service.channel;

import com.flauntik.config.HermesConfig;
import com.google.inject.Inject;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import lombok.extern.log4j.Log4j2;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Parses Meta's WhatsApp Cloud API webhook payload
 * ({@code entry[0].changes[0].value.messages[0]}). Unlike Twilio, Meta delivers every
 * number's messages to one app-level webhook URL, so the tenant is resolved from the
 * payload's {@code phone_number_id} rather than a path segment - see
 * {@link HermesConfig#findTenantIdByCloudApiPhoneNumberId}.
 */
@Log4j2
public class WhatsAppCloudApiInboundAdapter implements InboundChannelAdapter {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    private final HermesConfig hermesConfig;

    @Inject
    public WhatsAppCloudApiInboundAdapter(HermesConfig hermesConfig) {
        this.hermesConfig = hermesConfig;
    }

    @Override
    public JsonObject parseToCanonical(RoutingContext ctx) {
        JsonObject value = extractValue(ctx.body().asJsonObject());

        String phoneNumberId = value.getJsonObject("metadata", new JsonObject()).getString("phone_number_id");
        String tenantId = hermesConfig.findTenantIdByCloudApiPhoneNumberId(phoneNumberId);
        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "No tenant configured for WhatsApp Cloud API phone_number_id: " + phoneNumberId);
        }

        JsonObject message = value.getJsonArray("messages", new JsonArray()).getJsonObject(0);
        if (message == null) {
            throw new IllegalArgumentException("WhatsApp Cloud API payload has no messages[0]");
        }
        String from = message.getString("from");
        String body = message.getJsonObject("text", new JsonObject()).getString("body");

        return new JsonObject()
                .put("tenantId", tenantId)
                .put("from", from)
                .put("text", new JsonObject().put("body", body));
    }

    private static JsonObject extractValue(JsonObject body) {
        return body.getJsonArray("entry").getJsonObject(0)
                .getJsonArray("changes").getJsonObject(0)
                .getJsonObject("value");
    }

    /**
     * Meta's GET webhook-registration handshake: returns {@code hub.challenge} to echo
     * back if the mode/verify-token match, or {@code null} if the request should be
     * rejected (caller responds 403).
     */
    public String verifyChallenge(String mode, String verifyToken, String challenge) {
        String expected = hermesConfig.getWhatsAppCloudApiVerifyToken();
        if (!"subscribe".equals(mode) || expected == null || !expected.equals(verifyToken)) {
            return null;
        }
        return challenge;
    }

    /**
     * Validates the {@code X-Hub-Signature-256} header (HMAC-SHA256 of the raw request
     * body, keyed by the Meta app secret) before the payload is trusted.
     */
    public boolean verifySignature(Buffer rawBody, String signatureHeader) {
        String appSecret = hermesConfig.getWhatsAppCloudApiAppSecret();
        if (appSecret == null || signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            String computedHex = bytesToHex(mac.doFinal(rawBody.getBytes()));
            String providedHex = signatureHeader.substring(SIGNATURE_PREFIX.length());
            return MessageDigest.isEqual(
                    computedHex.getBytes(StandardCharsets.US_ASCII),
                    providedHex.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            log.error("Failed to verify WhatsApp Cloud API webhook signature", e);
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
