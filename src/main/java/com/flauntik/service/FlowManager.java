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

    private final Map<String, FlowStep> flow;
    private final Map<String, String> userState;
    private final Map<String, Map<String, String>> userHistory;

    @SneakyThrows
    @Inject
    public FlowManager(){
        userState = new ConcurrentHashMap<>();
        userHistory = new ConcurrentHashMap<>();
        this.flow = CommonUtil.mapper.readValue(Files.readAllBytes(Paths.get("src/main/resources/flow.json")), new TypeReference<Map<String, FlowStep>>() {});
    }

    public String getNextStep(String userId, String input) {
        String currentState = userState.getOrDefault(userId, "start");
        FlowStep currentFlow = flow.get(currentState);

        // Store user input in history
        userHistory.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put(currentState, input);

        switch (currentFlow.getType()) {
            case LIST:
            case BUTTON:
                Map<String, String> nextMap = (Map<String, String>) currentFlow.getNext();
                String nextState = nextMap.get(input);
                if (nextState != null) {
                    userState.put(userId, nextState);
                    return currentFlow.getMessage() + "\n" + formatOptions(currentFlow.getOptions());
                } else {
                    return "Invalid input. Try again.";
                }
            case MESSAGE:
                nextState = (String) currentFlow.getNext();
                userState.put(userId, nextState);
                return currentFlow.getMessage();
            case FUNCTION:
                performAction(currentFlow.getAction(), userId, input);
                nextState = (String) currentFlow.getNext();
                userState.put(userId, nextState);
                return "Action performed.";
            default:
                return "Invalid choice. Try again.";
        }
    }

    private void performAction(String action, String userId, String input) {
        // Implement the action logic here
        switch (action) {
            case "fetchOrderStatus":
                // Fetch order status logic
                break;
            case "sendPaymentLink":
                // Use user history to send payment link
                String name = userHistory.get(userId).get("ask_name");
                String age = userHistory.get(userId).get("ask_age");
                // Send payment link logic using name and age
                break;
            // Add more cases for other actions
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