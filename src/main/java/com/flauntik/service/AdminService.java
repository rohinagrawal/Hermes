package com.flauntik.service;

import com.flauntik.dto.request.LoggingRequest;
import com.flauntik.dto.response.Response;
import com.google.inject.Singleton;
import io.vertx.core.json.JsonObject;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

@Singleton
@Log4j2
public class AdminService {

    //https://stackoverflow.com/a/65151249
    public Response setLogLevel(JsonObject messageJO) {
        LoggingRequest request = messageJO.mapTo(LoggingRequest.class);
        LoggerContext ctx = (LoggerContext) LogManager.getContext(this.getClass().getClassLoader(), false);
        Configuration config = ctx.getConfiguration();
        LoggerConfig loggerConfig = config.getLoggerConfig("com.makemytrip");
        loggerConfig.setLevel(request.getLevel());
        ctx.updateLoggers();
        log.info("Logging Level Changed to {}", request.getLevel());
        log.debug("Debug check Logging Level Changed to {}", request.getLevel());
        return Response.getSuccessResponse();
    }
}
