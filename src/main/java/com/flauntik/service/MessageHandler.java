package com.flauntik.service;

import com.flauntik.dto.request.IncomingMessageRequest;
import com.google.inject.Inject;
import io.vertx.core.Future;
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

    public JsonObject handleIncomingMessage(IncomingMessageRequest request) {
        String userId = request.getFrom();
        String message = request.getText().getBody();

        String responseMessage = flowManager.getNextStep(userId, message);
        Future<JsonObject> responseFuture = whatsAppService.sendMessage(userId, responseMessage);
        JsonObject response = new JsonObject();
        if (responseFuture.succeeded()|| true){
            response.put("message", responseMessage);
        } else {
            response.put("error", responseFuture.cause().getMessage());
        }

        return response;
    }
}