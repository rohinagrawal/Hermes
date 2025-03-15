package com.flauntik.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Log4j2
public class CommonUtil {

    public static ObjectMapper mapper = null;

    static {
        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public static String joinIdentifiers(String delimiter, String... identifiers) {
        return String.join(delimiter, identifiers);
    }

    public static void flattenJson(String prefixKey, JsonNode node, Map<String, String> flatMap) {
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (value.isValueNode()) {
                    flatMap.put(prefixKey + key, value.asText());
                } else if (value.isObject()) {
                    flattenJson(prefixKey + key + ".", value, flatMap);
                } else if (value.isArray()) {
                    for (int i = 0; i < value.size(); i++) {
                        flattenJson(prefixKey + key + "[" + i + "].", value.get(i), flatMap);
                    }
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flattenJson(prefixKey + "[" + i + "].", node.get(i), flatMap);
            }
        } else {
            flatMap.put(prefixKey, node.asText());
        }
    }

    @SneakyThrows
    public static Map<String, String> flattenJson(String jsonString) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(jsonString);
        Map<String, String> flatMap = new HashMap<>();
        CommonUtil.flattenJson("", rootNode, flatMap);
        return flatMap;
    }

}
