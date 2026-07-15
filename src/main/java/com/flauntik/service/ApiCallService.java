package com.flauntik.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.flauntik.pojo.FlowStep;
import com.flauntik.util.CommonUtil;
import com.flauntik.util.TemplateUtil;
import com.google.inject.Inject;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import lombok.extern.log4j.Log4j2;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Executes a flow's API_CALL step against an arbitrary external HTTP API and extracts
 * fields from the JSON response into the user's context via responseMapping, so a flow
 * author can wire in any org API purely through flow.json instead of a hardcoded switch.
 */
@Log4j2
public class ApiCallService {

    public static final String CONTEXT_API_CALL_SUCCESS = "_apiCallSuccess";
    public static final String CONTEXT_API_CALL_ERROR = "_apiCallError";

    private final WebClient webClient;

    @Inject
    public ApiCallService(Vertx vertx) {
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

        Future<HttpResponse<Buffer>> responseFuture = (body != null) ? request.sendJsonObject(body) : request.send();

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
