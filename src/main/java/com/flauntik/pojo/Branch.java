package com.flauntik.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * One conditional routing rule on a BRANCH step. {@code when} is a templated expression
 * (e.g. {@code "{{patient_type}}"}) evaluated against the user's accumulated context —
 * so it can look back at an answer given several steps earlier. If the rendered value
 * equals {@code equals}, the flow routes to {@code next}. First matching rule wins;
 * if none match, the step's default {@code next} is used.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Branch {
    private String when;
    private String equals;
    private String next;
}
