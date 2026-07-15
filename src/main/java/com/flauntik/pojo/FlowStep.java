package com.flauntik.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.flauntik.enums.FlowStepType;
import com.flauntik.jackson.deserializer.NextFieldDeserializer;
import lombok.Data;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FlowStep {
    private FlowStepType type;
    private String message;
    private Map<String, String> options;

    @JsonDeserialize(using = NextFieldDeserializer.class)
    private Object next;

    // API_CALL fields
    private String apiUrl;
    private String apiMethod;
    private Map<String, String> apiHeaders;
    private Map<String, Object> apiBody;
    private Map<String, String> responseMapping;

    // PAYMENT fields
    private String paymentAmount;
    private String paymentCurrency;
    private String paymentDescription;

    // MEDIA fields
    private String mediaType;
    private String mediaUrl;
    private String mediaCaption;

    // Optional pre-registered Twilio Content Template, usable on any step type
    private String contentSid;
    private Map<String, String> contentVariables;
}
