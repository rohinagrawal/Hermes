package com.flauntik.service;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FlowManager {

    private final Map<String, String> userState = new ConcurrentHashMap<>();
    private final JsonObject flow;

    public FlowManager() throws Exception {
        String content = new String(Files.readAllBytes(Paths.get("src/main/resources/flow.json")));
        this.flow = new JsonObject(content);
    }

    public String getNextStep(String userId, String input) {
        String currentState = userState.getOrDefault(userId, "start");
        JsonObject currentFlow = flow.getJsonObject(currentState);

        if (currentFlow.getString("type").equals("list")) {
            userState.put(userId, currentFlow.getJsonObject("next").getString(input));
            return currentFlow.getString("message") + "\n" + formatOptions(currentFlow.getJsonObject("options"));
        } else if (currentFlow.getString("type").equals("message")) {
            userState.put(userId, currentFlow.getString("next"));
            return currentFlow.getString("message");
        } else {
            return "Invalid choice. Try again.";
        }
    }

    private String formatOptions(JsonObject options) {
        StringBuilder formattedOptions = new StringBuilder();
        for (String key : options.fieldNames()) {
            formattedOptions.append(key).append(". ").append(options.getString(key)).append("\n");
        }
        return formattedOptions.toString();
    }
}