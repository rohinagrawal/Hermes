package com.flauntik.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.hc.core5.http.HttpStatus;

import java.util.List;

@Data
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Response {

    @JsonProperty("success")
    private Boolean success;

    @JsonProperty("code")
    private Integer code;

    @JsonProperty("error")
    private String error;

    @JsonProperty("data")
    private Object data;

    public static Response getSuccessResponse() {
        return getSuccessResponse(HttpStatus.SC_SUCCESS);
    }

    public static Response getSuccessResponse(int code) {
        Response response = new Response();
        response.setSuccess(true);
        response.setCode(code);
        return response;
    }

    public static <T> Response getSuccessResponse(T data) {
        return getSuccessResponse(HttpStatus.SC_SUCCESS, data);
    }

    public static <T> Response getSuccessResponse(int code, T data) {
        Response response = new Response();
        response.setSuccess(true);
        response.setCode(code);
        if (data instanceof List<?>) {
            response.setData(new JsonArray((List) data));
        } else {
            response.setData(JsonObject.mapFrom(data));
        }
        return response;
    }

    public static Response getFailureResponse(int code) {
        Response response = new Response();
        response.setSuccess(false);
        response.setCode(code);
        return response;
    }

    public static Response getFailureResponse() {
        return getFailureResponse(HttpStatus.SC_INTERNAL_SERVER_ERROR);
    }

    public static Response getFailureResponse(int code, String errorMessage) {
        Response response = new Response();
        response.setSuccess(false);
        response.setCode(code);
        response.setError(errorMessage);
        return response;
    }

    public static Response getFailureResponse(String errorMessage) {
        return getFailureResponse(HttpStatus.SC_INTERNAL_SERVER_ERROR, errorMessage);
    }
}


