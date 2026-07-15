package com.flauntik.service;

import com.flauntik.dto.request.IncomingMessageRequest;
import com.flauntik.pojo.FlowStepResult;
import com.google.inject.Inject;
import io.vertx.core.json.JsonObject;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class MessageHandler {

    private final FlowManager flowManager;
    private final WhatsAppService whatsAppService;

    @Inject
    public MessageHandler(FlowManager flowManager, WhatsAppService whatsAppService) {
        this.flowManager = flowManager;
        this.whatsAppService = whatsAppService;
    }

    public JsonObject handleIncomingMessage(IncomingMessageRequest request) {
        String orgId = request.getOrgId();
        String userId = request.getFrom();
        String message = request.getText().getBody();

        FlowStepResult result = flowManager.getNextStep(orgId, userId, message)
                .toCompletionStage().toCompletableFuture().join();

        dispatchToWhatsApp(orgId, userId, result);

        JsonObject response = new JsonObject().put("message", result.getMessage());
        if (result.hasMedia()) {
            response.put("mediaType", result.getMediaType()).put("mediaUrl", result.getMediaUrl());
        }
        if (result.hasContentTemplate()) {
            response.put("contentSid", result.getContentSid());
        }
        return response;
    }

    /**
     * Fire-and-forget: the webhook ack (the returned JsonObject) must not block on
     * whether the outbound Twilio call itself succeeds.
     */
    private void dispatchToWhatsApp(String orgId, String userId, FlowStepResult result) {
        var send = result.hasContentTemplate()
                ? whatsAppService.sendTemplateMessage(orgId, userId, result.getContentSid(), result.getContentVariables())
                : result.hasMedia()
                ? whatsAppService.sendMediaMessage(orgId, userId, result.getMediaUrl(), result.getMediaCaption())
                : whatsAppService.sendMessage(orgId, userId, result.getMessage());

        send.onFailure(err -> log.warn("WhatsApp send failed for org={} user={}: {}", orgId, userId, err.getMessage()));
    }
}
