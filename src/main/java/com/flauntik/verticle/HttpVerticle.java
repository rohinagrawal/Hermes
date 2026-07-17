package com.flauntik.verticle;

import com.flauntik.config.HermesConfig;
import com.flauntik.constant.URIConstant;
import com.flauntik.dto.response.Response;
import com.flauntik.logger.AccessLogger;
import com.flauntik.service.channel.TwilioInboundAdapter;
import com.flauntik.service.channel.WhatsAppCloudApiInboundAdapter;
import com.flauntik.util.KafkaUtil;
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
import io.vertx.ext.web.api.contract.openapi3.OpenAPI3RouterFactory;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.validation.ValidationHandler;
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder;
import io.vertx.json.schema.SchemaParser;
import io.vertx.json.schema.SchemaRouter;
import io.vertx.json.schema.SchemaRouterOptions;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import lombok.extern.log4j.Log4j2;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.vertx.ext.web.validation.builder.Parameters.param;
import static io.vertx.json.schema.common.dsl.Schemas.stringSchema;

@Log4j2
public class HttpVerticle extends AbstractVerticle {

    private final HermesConfig hermesConfig;
    private final TwilioInboundAdapter twilioInboundAdapter;
    private final WhatsAppCloudApiInboundAdapter cloudApiInboundAdapter;

    /**
     * Non-null only when {@code HermesConfig.kafka} is present. Created in {@code start()}
     * (needs {@code vertx}, unavailable at construction time) and used by the two real
     * provider webhook handlers to produce-and-ack instead of dispatching synchronously -
     * see {@code produceAndAck}.
     */
    private KafkaProducer<String, String> kafkaProducer;

    @Inject
    public HttpVerticle(HermesConfig hermesConfig, TwilioInboundAdapter twilioInboundAdapter, WhatsAppCloudApiInboundAdapter cloudApiInboundAdapter) {
        this.hermesConfig = hermesConfig;
        this.twilioInboundAdapter = twilioInboundAdapter;
        this.cloudApiInboundAdapter = cloudApiInboundAdapter;
    }

    @Override
    public void start(Promise<Void> startPromise) throws Exception {

        HealthCheckHandler healthCheckHandler = HealthCheckHandler.create(vertx);
        registerHCHandler(healthCheckHandler);

        if (hermesConfig.getKafka() != null) {
            kafkaProducer = castProducer(KafkaUtil.intiaizeKafkaProducer(vertx, hermesConfig.getKafka().getProducer()));
        }

        OpenAPI3RouterFactory.create(vertx, "openapi/hermes.yaml")
                .onSuccess(routerFactory -> {
                    routerFactory.addHandlerByOperationId("healthCheck", healthCheckHandler);
                    routerFactory.addHandlerByOperationId("test", rc -> apiHandler(rc, URIConstant.TEST_EVENT));
                    routerFactory.addHandlerByOperationId("setLogging", rc -> apiHandler(rc, URIConstant.SET_LOGGING_EVENT));
                    routerFactory.addHandlerByOperationId("listTenants", rc -> apiHandler(rc, URIConstant.LIST_TENANTS_EVENT));
                    routerFactory.addHandlerByOperationId("incomingMessage", rc -> apiHandler(rc, URIConstant.INCOMING_MESSAGE_EVENT));
                    routerFactory.addHandlerByOperationId("whatsappCloudApiVerification", this::handleCloudApiVerification);

                    // The generated router installs its own BODY handler as the very first
                    // handler on its global route, and vertx-web rejects adding a PLATFORM-type
                    // handler (CorsHandler) after a BODY handler on the same route - so CORS and
                    // access logging are applied on an outer router instead, ahead of everything.
                    Router mainRouter = Router.router(vertx);
                    mainRouter.route().handler(new AccessLogger());
                    mainRouter.route().handler(CorsHandler.create()
                            .allowedHeaders(URIConstant.ALLOWED_HEADERS)
                            .allowedMethods(URIConstant.ALLOWED_METHODS));
                    mainRouter.mountSubRouter("/", routerFactory.getRouter());

                    // Raw-body routes are deliberately excluded from the OpenAPI spec above - HMAC
                    // signature verification needs the untouched raw bytes, which schema/body
                    // validation would consume first. Only their headers are validated here.
                    SchemaParser schemaParser = SchemaParser.createDraft7SchemaParser(
                            SchemaRouter.create(vertx, new SchemaRouterOptions()));

                    ValidationHandler twilioHeaderValidation = ValidationHandlerBuilder.create(schemaParser)
                            .headerParameter(param("Content-Type", stringSchema()))
                            .build();
                    mainRouter.post(URIConstant.TENANT_WEBHOOK)
                            .handler(BodyHandler.create())
                            .handler(twilioHeaderValidation)
                            .handler(this::handleTenantWebhook)
                            .failureHandler(this::handleValidationFailure);

                    ValidationHandler metaHeaderValidation = ValidationHandlerBuilder.create(schemaParser)
                            .headerParameter(param("Content-Type", stringSchema()))
                            .headerParameter(param("X-Hub-Signature-256", stringSchema()))
                            .build();
                    mainRouter.post(URIConstant.WHATSAPP_CLOUD_API_WEBHOOK)
                            .handler(BodyHandler.create())
                            .handler(metaHeaderValidation)
                            .handler(this::handleCloudApiWebhook)
                            .failureHandler(this::handleValidationFailure);

                    createHttpServer(startPromise, mainRouter);
                })
                .onFailure(startPromise::fail);
    }

    private void handleValidationFailure(RoutingContext routingContext) {
        Throwable cause = routingContext.failure();
        String message = cause == null ? "Bad Request" : cause.getMessage();
        log.warn("Rejecting request to {}: {}", routingContext.request().path(), message);
        routingContext.response().setStatusCode(HttpStatus.SC_BAD_REQUEST).putHeader(CONTENT_TYPE, ContentType.TEXT_PLAIN.getMimeType()).end(message);
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

    /** Twilio-style per-tenant webhook: tenant is already known from the :tenantId path segment. */
    private void handleTenantWebhook(RoutingContext routingContext) {
        if (kafkaProducer != null) {
            JsonObject canonical;
            try {
                canonical = twilioInboundAdapter.parseToCanonical(routingContext);
            } catch (Exception e) {
                routingContext.response().setStatusCode(HttpStatus.SC_BAD_REQUEST).end(e.getMessage());
                return;
            }
            produceAndAck(routingContext, canonical);
            return;
        }
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
     * Meta's shared WhatsApp Cloud API webhook - one URL serves every tenant's number, so the
     * tenant is resolved from the payload's phone_number_id rather than a path segment.
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

        if (kafkaProducer != null) {
            produceAndAck(routingContext, canonical);
            return;
        }
        dispatchToEventBus(routingContext, URIConstant.INCOMING_MESSAGE_EVENT, () -> canonical, 60000);
    }

    /**
     * Produces the canonical message to {@code incomingTopic}, keyed by {@code tenantId|from}
     * so a single user's messages always land on the same partition and are processed in
     * order by {@code KafkaIngestionVerticle} - required for the flow engine's per-user
     * state machine to behave correctly. Acks the HTTP request immediately once the
     * producer confirms the record is durably queued, with a generic body (not the flow
     * reply - that's computed later, out of band, by the consumer).
     */
    /** KafkaUtil returns a wildcard producer; the config fixes String key/value serializers. */
    @SuppressWarnings("unchecked")
    private static KafkaProducer<String, String> castProducer(KafkaProducer<?, ?> producer) {
        return (KafkaProducer<String, String>) producer;
    }

    private void produceAndAck(RoutingContext routingContext, JsonObject canonical) {
        String tenantId = canonical.getString("tenantId");
        String from = canonical.getString("from");
        String partitionKey = tenantId + "|" + from;

        KafkaProducerRecord<String, String> record = KafkaProducerRecord.create(
                hermesConfig.getKafka().getIncomingTopic(), partitionKey, canonical.encode());

        kafkaProducer.send(record, ar -> {
            if (ar.succeeded()) {
                routingContext.response()
                        .setStatusCode(HttpStatus.SC_OK)
                        .putHeader(CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType())
                        .end(JsonObject.mapFrom(Response.getSuccessResponse()).encode());
            } else {
                log.error("Failed to publish incoming message to Kafka for tenant={}", tenantId, ar.cause());
                routingContext.response().setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR).end();
            }
        });
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
