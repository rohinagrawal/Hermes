package com.flauntik.logger;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.LoggerFormatter;
import io.vertx.ext.web.handler.LoggerHandler;
import lombok.extern.log4j.Log4j2;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Function;

@Log4j2
public class AccessLogger implements LoggerHandler {
    private final DateFormat dateTimeFormat;

    public AccessLogger() {
        dateTimeFormat = new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z", Locale.US);
    }

    @Override
    public void handle(RoutingContext context) {
        long timestamp = System.currentTimeMillis();
        String remoteClient = this.getClientAddress(context.request().remoteAddress());
        HttpMethod method = context.request().method();
        String uri = context.request().uri();
        HttpVersion version = context.request().version();
        context.addBodyEndHandler((v) -> {
            this.log(context, timestamp, remoteClient, version, method, uri);
        });
        context.next();
    }

    private void log(RoutingContext context, long timestamp, String remoteClient, HttpVersion version, HttpMethod method, String uri) {
        HttpServerRequest request = context.request();
        long contentLength = 0L;
        String versionFormatted;
        contentLength = request.response().bytesWritten();

        versionFormatted = "-";
        switch (version) {
            case HTTP_1_0:
                versionFormatted = "HTTP/1.0";
                break;
            case HTTP_1_1:
                versionFormatted = "HTTP/1.1";
                break;
            case HTTP_2:
                versionFormatted = "HTTP/2.0";
        }

        MultiMap headers = request.headers();
        int status = request.response().getStatusCode();
        String referrer = headers.contains("referrer") ? headers.get("referrer") : headers.get("referer");
        String userAgent = request.headers().get("user-agent");
        referrer = referrer == null ? "-" : referrer;
        userAgent = userAgent == null ? "-" : userAgent;

        String client_forwarded_ip_str = headers.get("X-FORWARDED-FOR");
        String client_forwarded_ip = client_forwarded_ip_str != null ? client_forwarded_ip_str.split(",")[0] : "-";
        int response_commit_time = 0;
        String message = String.format("%s - - [%s] " +
                        "\"%s %s %s\" %d %d " +
                        "%d %d - %s",
                remoteClient, this.dateTimeFormat.format(new Date(timestamp)),
                method, uri, versionFormatted, status, contentLength,
                System.currentTimeMillis() - timestamp, response_commit_time, client_forwarded_ip);
        this.doLog(status, message);
    }

    private String getClientAddress(SocketAddress inetSocketAddress) {
        return inetSocketAddress == null ? null : inetSocketAddress.host();
    }

    protected void doLog(int status, String message) {
        if (status >= 500) {
            log.error(message);
        } else if (status >= 400) {
            log.warn(message);
        } else {
            log.info(message);
        }

    }

    @Override
    public LoggerHandler customFormatter(Function<HttpServerRequest, String> function) {
        return null;
    }

    @Override
    public LoggerHandler customFormatter(LoggerFormatter loggerFormatter) {
        return null;
    }
}

