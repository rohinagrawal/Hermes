package com.flauntik.verticle;

import com.flauntik.config.HermesConfig;
import com.flauntik.constant.URIConstant;
import com.flauntik.dto.response.Response;
import com.flauntik.logger.AccessLogger;
import com.google.inject.Inject;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.HealthCheckHandler;
import io.vertx.ext.healthchecks.Status;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import lombok.extern.log4j.Log4j2;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;

@Log4j2
public class HttpVerticle extends AbstractVerticle {

    private final HermesConfig hermesConfig;

    @Inject
    public HttpVerticle(HermesConfig hermesConfig) {
        this.hermesConfig = hermesConfig;
    }

    @Override
    public void start(Promise<Void> startPromise) throws Exception {

        Router router = Router.router(vertx);

        LoggerHandler loggerHandler = new AccessLogger();
        router.route().handler(loggerHandler);

        router.route().handler(CorsHandler.create().allowedHeaders(URIConstant.ALLOWED_HEADERS).allowedMethods(URIConstant.ALLOWED_METHODS));
        router.route().handler(BodyHandler.create()); // Enables JSON handling

        HealthCheckHandler healthCheckHandler = HealthCheckHandler.create(vertx);
        router.get(URIConstant.HEALTH_CHECK_API).produces(ContentType.APPLICATION_JSON.toString()).handler(healthCheckHandler);
        router.get(URIConstant.TEST).produces(ContentType.APPLICATION_JSON.getMimeType()).handler(rc -> apiHandler(rc, URIConstant.TEST_EVENT));
        router.post(URIConstant.SET_LOGGING).produces(ContentType.APPLICATION_JSON.getMimeType()).consumes(ContentType.APPLICATION_JSON.getMimeType()).handler(rc -> apiHandler(rc, URIConstant.SET_LOGGING_EVENT));

        // Webhook for receiving WhatsApp messages
        router.post(URIConstant.INCOMING_MESSAGE).consumes(ContentType.APPLICATION_JSON.getMimeType()).handler(rc -> apiHandler(rc, URIConstant.INCOMING_MESSAGE_EVENT));

        registerHCHandler(healthCheckHandler);
        createHttpServer(startPromise, router);
    }

    private void registerHCHandler(HealthCheckHandler healthCheckHandler) {
//        TODO : Complete the HealthCheck
        healthCheckHandler.register("application-status", statusPromise -> statusPromise.complete(Status.OK()));
    }

    private void createHttpServer(Promise<Void> promise, Router router) {
        HttpServerOptions serverOptions = new HttpServerOptions();
        serverOptions.setCompressionSupported(true);

        vertx.createHttpServer(serverOptions).requestHandler(router)
                .listen(hermesConfig.getPort(), result -> {
                    if (result.succeeded()) {
                        log.info("Http server is up at port {}", hermesConfig.getPort());
                        promise.complete();
                    } else {
                        log.error("Exception while getting HTTP Verticle up");
                        promise.fail(result.cause());
                    }
                });
    }

    private void apiHandler(RoutingContext routingContext, String address) {
        apiHandler(routingContext, address, 60000);
    }

    private void apiHandler(RoutingContext routingContext, String address, long timeout) {
        vertx.executeBlocking(future -> {
            try {
                JsonObject bodyAndParams = putParamsWithBody(routingContext.request().params().entries(),
                        routingContext.body() == null ? null : routingContext.body().asJsonObject());

                //setting eventBus's reply timeout
                vertx.eventBus().request(address, bodyAndParams, new DeliveryOptions().setHeaders(routingContext.request().headers()).setSendTimeout(timeout), (Handler<AsyncResult<Message<JsonObject>>>) asyncResult -> {
                    if (asyncResult.succeeded()) future.complete(asyncResult.result());
                    else future.fail(asyncResult.cause());
                });
            } catch (Exception e) {
                future.fail(new ReplyException(ReplyFailure.ERROR, HttpStatus.SC_BAD_REQUEST, e.getMessage()));
            }
        }, false, (Handler<AsyncResult<Message<JsonObject>>>) asyncResult -> {
            HttpServerResponse response = routingContext.response();
            try {
                if (!response.closed()) {
                    Response result = null;
                    if (asyncResult.succeeded()) {
                        result = (asyncResult.result().body()).mapTo(Response.class);
                    } else {
                        result = Response.getFailureResponse(HttpStatus.SC_INTERNAL_SERVER_ERROR, asyncResult.cause().getMessage());
                    }
                    response.setStatusCode(result.getCode());
                    response.putHeader(CONTENT_TYPE, routingContext.getAcceptableContentType() != null ? routingContext.getAcceptableContentType() : ContentType.APPLICATION_JSON.getMimeType());
                    response.end(JsonObject.mapFrom(result).encodePrettily());
                }
            } catch (Exception e) {
                routingContext.fail(e);
            }
        });
    }

    private JsonObject putParamsWithBody(List<Map.Entry<String, String>> paramList, JsonObject body) {
        JsonObject bodyAndParams = (body == null) ? new JsonObject() : body;
        for (Map.Entry<String, String> entry : paramList) {
            bodyAndParams.put(entry.getKey(), entry.getValue());
        }
        return bodyAndParams;
    }

}

