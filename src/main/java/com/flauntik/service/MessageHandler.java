package com.flauntik.service;

import com.google.inject.Inject;
import io.vertx.core.json.JsonObject;

public class MessageHandler {

    private final FlowManager flowManager;
    private final WhatsAppService whatsAppService;

    @Inject
    public MessageHandler(FlowManager flowManager, WhatsAppService whatsAppService) throws Exception {
        this.flowManager = flowManager;
        this.whatsAppService = whatsAppService;
    }

    public void handleIncomingMessage(String requestBody, io.vertx.core.Handler<JsonObject> responseHandler) {
        JsonObject json = new JsonObject(requestBody);
        String userId = json.getString("from");
        String message = json.getJsonObject("text").getString("body");

        String responseMessage = flowManager.getNextStep(userId, message);

        whatsAppService.sendMessage(userId, responseMessage)
                .onSuccess(responseHandler)
                .onFailure(err -> responseHandler.handle(new JsonObject().put("error", err.getMessage())));
    }

    public JsonObject handleIncomingMessage(JsonObject requestBody) {
        String userId = requestBody.getString("from");
        String message = requestBody.getJsonObject("text").getString("body");

        String responseMessage = flowManager.getNextStep(userId, message);

        return whatsAppService.sendMessage(userId, responseMessage).onFailure(err -> new RuntimeException(err)).result();
    }
}