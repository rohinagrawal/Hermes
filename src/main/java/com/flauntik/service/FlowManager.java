package com.flauntik.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.flauntik.enums.FlowStepType;
import com.flauntik.pojo.FlowStep;
import com.flauntik.util.CommonUtil;
import com.google.inject.Inject;
import lombok.SneakyThrows;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FlowManager {

    private final Map<String, String> userState;
    private final Map<String, FlowStep> flow;

    @SneakyThrows
    @Inject
    public FlowManager(){
        userState = new ConcurrentHashMap<>();
        this.flow = CommonUtil.mapper.readValue(Files.readAllBytes(Paths.get("src/main/resources/flow.json")), new TypeReference<Map<String, FlowStep>>() {});
    }

    public String getNextStep(String userId, String input) {
        String currentState = userState.getOrDefault(userId, "start");
        FlowStep currentFlow = flow.get(currentState);

        if (FlowStepType.LIST.equals(currentFlow.getType())) {
            Map<String, String> nextMap = (Map<String, String>) currentFlow.getNext();
            userState.put(userId, nextMap.get(input));
            return currentFlow.getMessage() + "\n" + formatOptions(currentFlow.getOptions());
        } else if (FlowStepType.BUTTON.equals(currentFlow.getType())) {
            Map<String, String> nextMap = (Map<String, String>) currentFlow.getNext();
            userState.put(userId, nextMap.get(input));
            return currentFlow.getMessage() + "\n" + formatOptions(currentFlow.getOptions());
        } else if (FlowStepType.MESSAGE.equals(currentFlow.getType())) {
            String nextState = (String) currentFlow.getNext();
            userState.put(userId, nextState);
            return currentFlow.getMessage();
        } else {
            return "Invalid choice. Try again.";
        }
    }

    private String formatOptions(Map<String, String> options) {
        StringBuilder formattedOptions = new StringBuilder();
        for (String key : options.keySet()) {
            formattedOptions.append(key).append(". ").append(options.get(key)).append("\n");
        }
        return formattedOptions.toString();
    }
}