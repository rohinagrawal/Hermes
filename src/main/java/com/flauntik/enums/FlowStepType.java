package com.flauntik.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.util.Arrays;

@Log4j2
@Getter
public enum FlowStepType{

    MESSAGE("message"),
    LIST("list"),
    BUTTON("button"),
    UNKNOWN("unknown");

    private final String name;

    FlowStepType(String name) {
        this.name = name;
    }

    @JsonCreator
    public static FlowStepType getByValue(String value) {
        try {
            return FlowStepType.valueOf(value);
        } catch (Exception e) {
            FlowStepType result = Arrays.stream(FlowStepType.values()).filter(flowStepType -> flowStepType.name().equals(value)).findFirst().orElse(UNKNOWN);
            if (result == UNKNOWN)
                log.error("Not registered Enum Value : {}, Class : {}", value, FlowStepType.class);
            return result;
        }
    }
}
