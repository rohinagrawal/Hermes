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
}
