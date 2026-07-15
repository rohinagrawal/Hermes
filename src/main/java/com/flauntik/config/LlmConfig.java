package com.flauntik.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Per-org configuration for the LLM fallback agent that answers messages which don't
 * match the current workflow menu. The API key is never stored here — it's read from
 * the {@code ANTHROPIC_API_KEY} environment variable by {@code LlmAgentService}.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmConfig {
    private boolean enabled = false;
    private String model = "claude-opus-4-8";
    private String systemPrompt;
    private Integer maxTokens = 400;
}
