package com.flauntik.util;

import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.lookup.StringLookup;

import java.util.Map;

public class TemplateUtil {

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

        StringLookup lookup = key -> {
            Object value = context.get(key.trim());
            return value == null ? null : String.valueOf(value);
        };

        StringSubstitutor substitutor = new StringSubstitutor(lookup, "{{", "}}", '$');
        // Guarantees a single pass: without this, a resolved value that itself contains
        // "{{...}}"-looking text (e.g. a user's free-text answer) would get re-scanned and
        // substituted again, which the original hand-rolled regex never did.
        substitutor.setDisableSubstitutionInValues(true);
        return substitutor.replace(template);
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
