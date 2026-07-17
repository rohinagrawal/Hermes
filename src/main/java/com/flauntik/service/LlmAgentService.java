package com.flauntik.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.flauntik.config.HermesConfig;
import com.flauntik.config.LlmConfig;
import com.flauntik.config.TenantConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import lombok.extern.log4j.Log4j2;

import java.util.stream.Collectors;

/**
 * LLM fallback agent: when a user's message doesn't match the current workflow menu,
 * this generates a helpful, on-brand reply via Claude instead of a canned "invalid
 * input" error. Enabled per-tenant (TenantConfig.llm.enabled) and only when the process has
 * an ANTHROPIC_API_KEY — otherwise it reports unavailable and FlowManager falls back to
 * the default re-prompt, so the bot still works without any LLM configured.
 */
@Log4j2
@Singleton
public class LlmAgentService {

    private final Vertx vertx;
    private final HermesConfig hermesConfig;
    private final AnthropicClient client;

    @Inject
    public LlmAgentService(Vertx vertx, HermesConfig hermesConfig) {
        this.vertx = vertx;
        this.hermesConfig = hermesConfig;
        this.client = initClient();
    }

    private AnthropicClient initClient() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("ANTHROPIC_API_KEY not set — LLM fallback disabled; off-menu messages get the default re-prompt");
            return null;
        }
        try {
            AnthropicClient c = AnthropicOkHttpClient.fromEnv();
            log.info("LLM fallback agent initialised (Anthropic client ready)");
            return c;
        } catch (Exception e) {
            log.error("Failed to initialise Anthropic client — LLM fallback disabled", e);
            return null;
        }
    }

    /** True only when the client is ready AND this tenant opted into the LLM fallback. */
    public boolean isAvailableFor(String tenantId) {
        if (client == null) {
            return false;
        }
        LlmConfig llm = hermesConfig.getTenantConfig(tenantId).getLlm();
        return llm != null && llm.isEnabled();
    }

    /**
     * Generates a reply to an off-menu message. {@code menuText} is the current menu the
     * user is looking at, passed to the model so it can answer and then steer the user
     * back to a valid option. Runs the (blocking) SDK call on Vert.x's worker pool.
     */
    public Future<String> generateReply(String tenantId, String userMessage, String menuText) {
        LlmConfig cfg = hermesConfig.getTenantConfig(tenantId).getLlm();
        String system = buildSystemPrompt(cfg, menuText);

        return vertx.executeBlocking((Promise<String> promise) -> {
            try {
                MessageCreateParams params = MessageCreateParams.builder()
                        .model(cfg.getModel())
                        .maxTokens((long) cfg.getMaxTokens())
                        .system(system)
                        .addUserMessage(userMessage)
                        .build();

                Message response = client.messages().create(params);
                String text = response.content().stream()
                        .flatMap(block -> block.text().stream())
                        .map(textBlock -> textBlock.text())
                        .collect(Collectors.joining("\n"))
                        .trim();

                promise.complete(text.isEmpty() ? null : text);
            } catch (Exception e) {
                promise.fail(e);
            }
        }, false);
    }

    private String buildSystemPrompt(LlmConfig cfg, String menuText) {
        StringBuilder sb = new StringBuilder();
        sb.append(cfg.getSystemPrompt() == null || cfg.getSystemPrompt().isBlank()
                ? "You are a helpful WhatsApp assistant for a business."
                : cfg.getSystemPrompt());
        sb.append("\n\nYou are replying inside WhatsApp. Keep replies short (1-3 sentences), warm, and in plain text (no markdown, no headings). ");
        sb.append("The user just sent a message that didn't match the current menu. Answer their question helpfully if you can, then gently point them back to the menu options. ");
        sb.append("Do not invent prices, availability, medical, legal, or financial advice; if you're unsure, say so and suggest the relevant menu option (e.g. booking a consultation).");
        if (menuText != null && !menuText.isBlank()) {
            sb.append("\n\nThe menu the user is currently looking at:\n").append(menuText);
        }
        return sb.toString();
    }
}
