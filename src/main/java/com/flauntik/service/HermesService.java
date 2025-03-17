package com.flauntik.service;

import com.flauntik.dto.request.IncomingMessageRequest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;

public class HermesService {

    private final MessageHandler messageHandler;

    @Inject
    public HermesService(MessageHandler messageHandler) {
        this.messageHandler = messageHandler;
    }

    public JsonObject handleIncomingMessage(JsonObject requestBody) {
        return messageHandler.handleIncomingMessage(requestBody.mapTo(IncomingMessageRequest.class));
    }
}
