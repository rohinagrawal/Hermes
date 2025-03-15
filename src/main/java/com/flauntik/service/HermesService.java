package com.flauntik.service;

import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;

public class HermesService {

    private final MessageHandler messageHandler;

    @Inject
    public HermesService(MessageHandler messageHandler) {
        this.messageHandler = messageHandler;
    }

    public void handleIncomingMessage(JsonObject requestBody) {
        messageHandler.handleIncomingMessage(requestBody);
    }
}
