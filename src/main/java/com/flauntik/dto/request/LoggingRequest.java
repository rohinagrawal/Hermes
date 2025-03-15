package com.flauntik.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.apache.logging.log4j.Level;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoggingRequest {
    private Level level;
}

