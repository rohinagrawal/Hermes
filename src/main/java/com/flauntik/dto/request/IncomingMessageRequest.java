package com.flauntik.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.flauntik.pojo.IncomingMessageText;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class IncomingMessageRequest {
    private String from;
    private IncomingMessageText text;
}
