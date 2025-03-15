package com.flauntik.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;

public class WhatsAppService {

    private final WebClient webClient;
    private static final String TWILIO_API_URL = "https://api.twilio.com/2010-04-01/Accounts/{ACCOUNT_SID}/Messages.json";
    private static final String ACCOUNT_SID = "your_twilio_account_sid";
    private static final String AUTH_TOKEN = "your_twilio_auth_token";
    private static final String FROM_WHATSAPP = "whatsapp:+your_twilio_number";

    public WhatsAppService() {
        this.webClient = WebClient.create(io.vertx.core.Vertx.vertx());
    }

    public Future<JsonObject> sendMessage(String to, String message) {
        JsonObject requestBody = new JsonObject()
                .put("To", "whatsapp:" + to)
                .put("From", FROM_WHATSAPP)
                .put("Body", message);

        return webClient.postAbs(TWILIO_API_URL.replace("{ACCOUNT_SID}", ACCOUNT_SID))
                .putHeader("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString((ACCOUNT_SID + ":" + AUTH_TOKEN).getBytes()))
                .putHeader("Content-Type", "application/x-www-form-urlencoded")
                .sendJsonObject(requestBody)
                .map(response -> response.bodyAsJsonObject());
    }

    public Future<JsonObject> sendListMessage(String to, JsonObject listMessage) {
        JsonObject requestBody = new JsonObject()
                .put("To", "whatsapp:" + to)
                .put("From", FROM_WHATSAPP)
                .put("Body", listMessage.encode());

        return webClient.postAbs(TWILIO_API_URL.replace("{ACCOUNT_SID}", ACCOUNT_SID))
                .putHeader("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString((ACCOUNT_SID + ":" + AUTH_TOKEN).getBytes()))
                .putHeader("Content-Type", "application/x-www-form-urlencoded")
                .sendJsonObject(requestBody)
                .map(response -> response.bodyAsJsonObject());
    }

    public Future<JsonObject> sendReplyButtonMessage(String to, JsonObject replyButtonMessage) {
        JsonObject requestBody = new JsonObject()
                .put("To", "whatsapp:" + to)
                .put("From", FROM_WHATSAPP)
                .put("Body", replyButtonMessage.encode());

        return webClient.postAbs(TWILIO_API_URL.replace("{ACCOUNT_SID}", ACCOUNT_SID))
                .putHeader("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString((ACCOUNT_SID + ":" + AUTH_TOKEN).getBytes()))
                .putHeader("Content-Type", "application/x-www-form-urlencoded")
                .sendJsonObject(requestBody)
                .map(response -> response.bodyAsJsonObject());
    }
}