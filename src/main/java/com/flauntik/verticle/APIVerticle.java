package com.flauntik.verticle;

import com.flauntik.config.HermesConfig;
import com.flauntik.constant.URIConstant;
import com.flauntik.dto.response.Response;
import com.flauntik.service.AdminService;
import com.flauntik.service.HermesService;
import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import io.vertx.core.*;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;
import io.vertx.core.json.JsonObject;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.hc.core5.http.HttpStatus;

import static com.flauntik.constant.URIConstant.*;
import static org.apache.hc.core5.http.HttpStatus.SC_INTERNAL_SERVER_ERROR;

@Log4j2
public class APIVerticle extends AbstractVerticle {

    private final AdminService adminService;
    private final HermesService hermesService;

    @Inject
    public APIVerticle(HermesConfig config, AdminService adminService, HermesService hermesService) {
        Preconditions.checkNotNull(config);
        this.adminService = adminService;
        this.hermesService = hermesService;
    }

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        super.start();
        this.vertx.eventBus().consumer(TEST, (Handler<Message<JsonObject>>) message -> handleMessage(TEST, message));
        this.vertx.eventBus().consumer(SET_LOGGING_EVENT, (Handler<Message<JsonObject>>) message -> handleMessage(SET_LOGGING_EVENT, message));
        this.vertx.eventBus().consumer(URIConstant.INCOMING_MESSAGE_EVENT, (Handler<Message<JsonObject>>) message -> handleMessage(INCOMING_MESSAGE_EVENT, message));
        startPromise.complete();
    }

    private void handleMessage(String address, final Message<JsonObject> message) {
        vertx.executeBlocking(future -> {
            try {
                Response response = delegateMethodHandlers(address, message);
                future.complete(JsonObject.mapFrom(response));
            } catch (Exception e) {
                log.error(address, message.body(), e);
                future.fail(e);
            }
        }, false, (Handler<AsyncResult<JsonObject>>) asyncResult -> {
            if (asyncResult.succeeded()) {
                JsonObject result = asyncResult.result();
                if (ObjectUtils.isEmpty(result)) {
                    result = JsonObject.mapFrom(Response.getSuccessResponse());
                }
                message.reply(result);
            } else {
                Throwable throwable = asyncResult.cause();
                if (throwable instanceof ReplyException exception) {
                    message.fail(exception.failureCode(), exception.getMessage());
                } else {
                    message.fail(SC_INTERNAL_SERVER_ERROR, throwable.getMessage());
                }
            }
        });
    }

    private Response delegateMethodHandlers(String address, final Message<JsonObject> message) throws Exception {
        JsonObject messageJO = message.body();
        MultiMap headers = message.headers();
        Response response = null;
        log.debug("Received message for address: {} with headers: {} and body: {}", address, headers, messageJO);
        switch (address) {
            case TEST_EVENT -> {
                response = Response.getSuccessResponse();
            }
            case SET_LOGGING_EVENT -> {
                response = adminService.setLogLevel(messageJO);
            }
            case INCOMING_MESSAGE_EVENT -> {
                response = Response.getSuccessResponse(hermesService.handleIncomingMessage(messageJO));
            }
            default ->
                    throw new ReplyException(ReplyFailure.NO_HANDLERS, HttpStatus.SC_NOT_FOUND, "No Handler Configured");
        }
        return response;
    }
}



