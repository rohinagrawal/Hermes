package com.flauntik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.flauntik.pojo.FlowStep;
import com.flauntik.util.CommonUtil;
import com.flauntik.util.TemplateUtil;
import com.google.inject.Inject;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import lombok.extern.log4j.Log4j2;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes a flow's API_CALL step against an arbitrary external HTTP API and extracts
 * fields from the JSON response into the user's context via responseMapping, so a flow
 * author can wire in any tenant API purely through flow.json instead of a hardcoded switch.
 */
@Log4j2
public class ApiCallService {

    public static final String CONTEXT_API_CALL_SUCCESS = "_apiCallSuccess";
    public static final String CONTEXT_API_CALL_ERROR = "_apiCallError";

    private final Vertx vertx;
    private final WebClient webClient;

    /**
     * A step's apiUrl can point anywhere - different tenants, different steps, different
     * hosts entirely. A single shared circuit breaker would let one broken host's failures
     * trip the breaker for every unrelated tenant's calls, so breakers are keyed per host
     * and created lazily the first time that host is called.
     */
    private final Map<String, CircuitBreaker> breakersByHost = new ConcurrentHashMap<>();

    @Inject
    public ApiCallService(Vertx vertx) {
        this.vertx = vertx;
        this.webClient = WebClient.create(vertx);
    }

    public Future<Map<String, Object>> execute(FlowStep step, Map<String, Object> context) {
        String url = TemplateUtil.render(step.getApiUrl(), context);
        HttpMethod method = HttpMethod.valueOf(Optional.ofNullable(step.getApiMethod()).orElse("GET").toUpperCase());
        Map<String, String> headers = TemplateUtil.renderMap(step.getApiHeaders(), context);
        JsonObject body = renderBody(step.getApiBody(), context);

        HttpRequest<Buffer> request = webClient.requestAbs(method, url);
        if (headers != null) {
            headers.forEach(request::putHeader);
        }

        CircuitBreaker breaker = breakerForHost(url);
        Future<HttpResponse<Buffer>> responseFuture = breaker.<HttpResponse<Buffer>>execute(promise -> {
            Future<HttpResponse<Buffer>> callFuture = (body != null) ? request.sendJsonObject(body) : request.send();
            callFuture.onComplete(ar -> {
                if (ar.succeeded() && ar.result().statusCode() >= 500) {
                    // A 5xx means the upstream itself is unhealthy - count it as a breaker
                    // failure. A 2xx/3xx/4xx is a completed call (even a 404 is a normal
                    // business outcome, not "the dependency is down") and still flows
                    // through to extractContext below for its usual success/failure mapping.
                    promise.fail("Upstream returned HTTP " + ar.result().statusCode());
                } else {
                    promise.handle(ar);
                }
            });
        });

        return responseFuture
                .map(response -> extractContext(step, response))
                .recover(err -> {
                    log.error("API_CALL to {} failed", url, err);
                    Map<String, Object> extracted = new LinkedHashMap<>();
                    extracted.put(CONTEXT_API_CALL_SUCCESS, false);
                    extracted.put(CONTEXT_API_CALL_ERROR, err.getMessage());
                    return Future.succeededFuture(extracted);
                });
    }

    private CircuitBreaker breakerForHost(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (Exception e) {
            host = url;
        }
        String breakerKey = host == null ? url : host;
        return breakersByHost.computeIfAbsent(breakerKey, key -> CircuitBreaker.create(
                "api-call-" + key, vertx,
                new CircuitBreakerOptions()
                        .setMaxFailures(5)
                        .setTimeout(10_000)
                        .setResetTimeout(30_000)
                        // vertx-circuit-breaker's own default is only 10s, too short for
                        // failures spaced a few seconds apart to accumulate to maxFailures.
                        .setFailuresRollingWindow(60_000)));
    }

    private Map<String, Object> extractContext(FlowStep step, HttpResponse<Buffer> response) {
        Map<String, Object> extracted = new LinkedHashMap<>();
        boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
        extracted.put(CONTEXT_API_CALL_SUCCESS, success);
        if (!success) {
            extracted.put(CONTEXT_API_CALL_ERROR, "HTTP " + response.statusCode());
        }

        if (step.getResponseMapping() != null && !step.getResponseMapping().isEmpty()) {
            JsonNode json = tryParseJson(response.bodyAsString());
            if (json != null) {
                step.getResponseMapping().forEach((varName, pointer) -> {
                    JsonNode node = json.at(pointer);
                    if (!node.isMissingNode()) {
                        extracted.put(varName, node.isValueNode() ? node.asText() : node.toString());
                    }
                });
            }
        }
        return extracted;
    }

    private JsonNode tryParseJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return CommonUtil.mapper.readTree(body);
        } catch (Exception e) {
            log.warn("API_CALL response was not valid JSON, skipping responseMapping");
            return null;
        }
    }

    private JsonObject renderBody(Map<String, Object> apiBody, Map<String, Object> context) {
        if (apiBody == null) {
            return null;
        }
        JsonObject rendered = new JsonObject();
        apiBody.forEach((key, value) -> rendered.put(key, value instanceof String s ? TemplateUtil.render(s, context) : value));
        return rendered;
    }
}
