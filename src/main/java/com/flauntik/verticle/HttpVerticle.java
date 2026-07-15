package com.flauntik.verticle;

import com.flauntik.config.HermesConfig;
import com.flauntik.constant.URIConstant;
import com.flauntik.dto.response.Response;
import com.flauntik.logger.AccessLogger;
import com.flauntik.service.channel.TwilioInboundAdapter;
import com.flauntik.service.channel.WhatsAppCloudApiInboundAdapter;
import com.google.inject.Inject;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
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
    private final TwilioInboundAdapter twilioInboundAdapter;
    private final WhatsAppCloudApiInboundAdapter cloudApiInboundAdapter;

    @Inject
    public HttpVerticle(HermesConfig hermesConfig, TwilioInboundAdapter twilioInboundAdapter, WhatsAppCloudApiInboundAdapter cloudApiInboundAdapter) {
        this.hermesConfig = hermesConfig;
        this.twilioInboundAdapter = twilioInboundAdapter;
        this.cloudApiInboundAdapter = cloudApiInboundAdapter;
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
        router.get(URIConstant.LIST_ORGS).produces(ContentType.APPLICATION_JSON.getMimeType()).handler(rc -> apiHandler(rc, URIConstant.LIST_ORGS_EVENT));

        // Webhook for receiving WhatsApp messages, routed per org via the :orgId path segment
        router.post(URIConstant.INCOMING_MESSAGE).consumes(ContentType.APPLICATION_JSON.getMimeType()).handler(rc -> apiHandler(rc, URIConstant.INCOMING_MESSAGE_EVENT));

        // Real per-org provider webhook (Twilio: webhook URL configured per WhatsApp number in the console)
        router.post(URIConstant.ORG_WEBHOOK).handler(this::handleOrgWebhook);

        // Real shared provider webhook (Meta WhatsApp Cloud API: one app-level webhook for every org's number)
        router.get(URIConstant.WHATSAPP_CLOUD_API_WEBHOOK).handler(this::handleCloudApiVerification);
        router.post(URIConstant.WHATSAPP_CLOUD_API_WEBHOOK).handler(this::handleCloudApiWebhook);

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
        dispatchToEventBus(routingContext, address, () -> {
            JsonObject bodyAndParams = putParamsWithBody(routingContext.request().params().entries(),
                    routingContext.body() == null ? null : routingContext.body().asJsonObject());
            routingContext.pathParams().forEach(bodyAndParams::put);
            return bodyAndParams;
        }, timeout);
    }

    /** Twilio-style per-org webhook: org is already known from the :orgId path segment. */
    private void handleOrgWebhook(RoutingContext routingContext) {
        dispatchToEventBus(routingContext, URIConstant.INCOMING_MESSAGE_EVENT,
                () -> twilioInboundAdapter.parseToCanonical(routingContext), 60000);
    }

    /** Meta's GET webhook-registration handshake - echoes hub.challenge iff mode/verify-token match. */
    private void handleCloudApiVerification(RoutingContext routingContext) {
        String mode = routingContext.request().getParam("hub.mode");
        String verifyToken = routingContext.request().getParam("hub.verify_token");
        String challenge = routingContext.request().getParam("hub.challenge");
        String echoed = cloudApiInboundAdapter.verifyChallenge(mode, verifyToken, challenge);
        if (echoed != null) {
            routingContext.response().setStatusCode(HttpStatus.SC_OK).putHeader(CONTENT_TYPE, "text/plain").end(echoed);
        } else {
            log.warn("Rejecting WhatsApp Cloud API webhook verification: mode={}", mode);
            routingContext.response().setStatusCode(HttpStatus.SC_FORBIDDEN).end();
        }
    }

    /**
     * Meta's shared WhatsApp Cloud API webhook - one URL serves every org's number, so the
     * org is resolved from the payload's phone_number_id rather than a path segment.
     */
    private void handleCloudApiWebhook(RoutingContext routingContext) {
        Buffer rawBody = routingContext.body() == null ? Buffer.buffer() : routingContext.body().buffer();
        String signature = routingContext.request().getHeader("X-Hub-Signature-256");
        if (!cloudApiInboundAdapter.verifySignature(rawBody, signature)) {
            log.warn("Rejecting WhatsApp Cloud API webhook: invalid or missing X-Hub-Signature-256");
            routingContext.response().setStatusCode(HttpStatus.SC_UNAUTHORIZED).end();
            return;
        }

        JsonObject canonical;
        try {
            canonical = cloudApiInboundAdapter.parseToCanonical(routingContext);
        } catch (Exception e) {
            // Meta disables a webhook that doesn't return 200, even for payloads we can't route
            // (e.g. an unrecognized phone_number_id) - so ack anyway and just drop the message.
            log.warn("Dropping unroutable WhatsApp Cloud API webhook: {}", e.getMessage());
            routingContext.response().setStatusCode(HttpStatus.SC_OK).end();
            return;
        }

        dispatchToEventBus(routingContext, URIConstant.INCOMING_MESSAGE_EVENT, () -> canonical, 60000);
    }

    @FunctionalInterface
    private interface CanonicalBodySupplier {
        JsonObject get() throws Exception;
    }

    private void dispatchToEventBus(RoutingContext routingContext, String address, CanonicalBodySupplier bodySupplier, long timeout) {
        vertx.executeBlocking(future -> {
            try {
                JsonObject body = bodySupplier.get();

                //setting eventBus's reply timeout
                vertx.eventBus().request(address, body, new DeliveryOptions().setHeaders(routingContext.request().headers()).setSendTimeout(timeout), (Handler<AsyncResult<Message<JsonObject>>>) asyncResult -> {
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
