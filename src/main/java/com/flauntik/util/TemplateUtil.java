package com.flauntik.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TemplateUtil {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    private TemplateUtil() {
    }

    /**
     * Replaces {{key}} tokens in the template with values from context.
     * Unresolved tokens are left as-is so authoring mistakes are visible rather than silently blanked.
     */
    public static String render(String template, Map<String, Object> context) {
        if (template == null) {
            return null;
        }
        if (context == null || context.isEmpty()) {
            return template;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object value = context.get(matcher.group(1));
            matcher.appendReplacement(result, value == null ? matcher.group(0) : Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static Map<String, String> renderMap(Map<String, String> templates, Map<String, Object> context) {
        if (templates == null) {
            return null;
        }
        Map<String, String> rendered = new java.util.LinkedHashMap<>();
        templates.forEach((key, value) -> rendered.put(key, render(value, context)));
        return rendered;
    }
}
