package com.flauntik.service;

import com.flauntik.enums.FlowStepType;
import com.flauntik.pojo.FlowStep;
import com.flauntik.pojo.FlowStepResult;
import com.flauntik.pojo.payment.PaymentRequest;
import com.flauntik.service.flow.OrgFlowRegistry;
import com.flauntik.service.payment.PaymentProvider;
import com.flauntik.util.TemplateUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.vertx.core.Future;
import lombok.extern.log4j.Log4j2;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives a per-org, per-user conversation through that org's flow graph.
 *
 * MESSAGE/LIST/BUTTON/MEDIA are "interactive" steps: they're rendered and sent to the
 * user, then execution pauses at that step id until the user's next message arrives.
 * API_CALL/PAYMENT are "automatic" steps: they run immediately (no user input needed)
 * and cascade straight through to the next step, branching on success/failure when
 * `next` is a map.
 */
@Log4j2
@Singleton
public class FlowManager {

    private final OrgFlowRegistry orgFlowRegistry;
    private final ApiCallService apiCallService;
    private final PaymentProvider paymentProvider;

    private final Map<String, String> userState = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> userContext = new ConcurrentHashMap<>();

    @Inject
    public FlowManager(OrgFlowRegistry orgFlowRegistry, ApiCallService apiCallService, PaymentProvider paymentProvider) {
        this.orgFlowRegistry = orgFlowRegistry;
        this.apiCallService = apiCallService;
        this.paymentProvider = paymentProvider;
    }

    public Future<FlowStepResult> getNextStep(String orgId, String userId, String input) {
        Map<String, FlowStep> flow = orgFlowRegistry.getFlow(orgId);
        String key = sessionKey(orgId, userId);
        Map<String, Object> context = userContext.computeIfAbsent(key, k -> new ConcurrentHashMap<>());

        String currentStateId = userState.get(key);
        if (currentStateId == null) {
            // Brand new session: the user hasn't been shown anything yet, so their first
            // message is a trigger to render `start`, not an answer to it.
            return cascade(orgId, key, "start", flow, context);
        }

        FlowStep currentStep = flow.get(currentStateId);
        if (currentStep == null) {
            log.warn("org={} user={} was parked on undefined step '{}', resetting to start", orgId, userId, currentStateId);
            userState.remove(key);
            return cascade(orgId, key, "start", flow, context);
        }

        context.put(currentStateId, input);

        return switch (currentStep.getType()) {
            case LIST, BUTTON -> {
                @SuppressWarnings("unchecked")
                Map<String, String> nextMap = (Map<String, String>) currentStep.getNext();
                String nextId = nextMap.get(input);
                yield nextId == null
                        ? Future.succeededFuture(renderInvalidInput(currentStep, context))
                        : cascade(orgId, key, nextId, flow, context);
            }
            case MESSAGE, MEDIA -> cascade(orgId, key, (String) currentStep.getNext(), flow, context);
            default -> {
                log.warn("org={} user={} was parked on non-interactive step '{}', resetting to start", orgId, userId, currentStateId);
                userState.remove(key);
                yield cascade(orgId, key, "start", flow, context);
            }
        };
    }

    private Future<FlowStepResult> cascade(String orgId, String key, String stepId, Map<String, FlowStep> flow, Map<String, Object> context) {
        FlowStep step = flow.get(stepId);
        if (step == null) {
            log.warn("org={} flow references undefined step '{}', resetting to start", orgId, stepId);
            stepId = "start";
            step = flow.get("start");
        }
        String resolvedStepId = stepId;
        FlowStep resolvedStep = step;

        return switch (step.getType()) {
            case MESSAGE, LIST, BUTTON, MEDIA -> {
                userState.put(key, resolvedStepId);
                yield Future.succeededFuture(renderStep(resolvedStep, context));
            }
            case API_CALL -> apiCallService.execute(resolvedStep, context).compose(extracted -> {
                context.putAll(extracted);
                boolean success = Boolean.TRUE.equals(extracted.get(ApiCallService.CONTEXT_API_CALL_SUCCESS));
                return cascade(orgId, key, resolveBranchNext(resolvedStep, success), flow, context);
            });
            case PAYMENT -> executePayment(orgId, key, resolvedStep, flow, context);
            default -> Future.failedFuture(new IllegalStateException("Unsupported step type " + step.getType() + " for step " + resolvedStepId));
        };
    }

    private Future<FlowStepResult> executePayment(String orgId, String key, FlowStep step, Map<String, FlowStep> flow, Map<String, Object> context) {
        String userId = key.substring(key.indexOf('|') + 1);
        PaymentRequest request = new PaymentRequest(
                orgId,
                userId,
                TemplateUtil.render(step.getPaymentAmount(), context),
                TemplateUtil.render(step.getPaymentCurrency(), context),
                TemplateUtil.render(step.getPaymentDescription(), context));

        return paymentProvider.createPaymentLink(request)
                .compose(link -> {
                    context.put("paymentLink", link.getUrl());
                    context.put("paymentReferenceId", link.getReferenceId());
                    return cascade(orgId, key, resolveBranchNext(step, true), flow, context);
                })
                .recover(err -> {
                    log.error("PAYMENT step failed for org={} user={}", orgId, userId, err);
                    context.put("paymentError", err.getMessage());
                    return cascade(orgId, key, resolveBranchNext(step, false), flow, context);
                });
    }

    @SuppressWarnings("unchecked")
    private String resolveBranchNext(FlowStep step, boolean success) {
        Object next = step.getNext();
        if (next instanceof String s) {
            return s;
        }
        Map<String, String> nextMap = (Map<String, String>) next;
        String branchKey = success ? "success" : "failure";
        return nextMap.getOrDefault(branchKey, nextMap.values().iterator().next());
    }

    private FlowStepResult renderStep(FlowStep step, Map<String, Object> context) {
        FlowStepResult result = new FlowStepResult();
        String message = TemplateUtil.render(step.getMessage(), context);
        result.setMessage(step.getType() == FlowStepType.LIST || step.getType() == FlowStepType.BUTTON
                ? message + "\n" + formatOptions(step.getOptions())
                : message);

        if (step.getType() == FlowStepType.MEDIA) {
            result.setMediaType(step.getMediaType());
            result.setMediaUrl(TemplateUtil.render(step.getMediaUrl(), context));
            String caption = TemplateUtil.render(step.getMediaCaption(), context);
            result.setMediaCaption(caption);
            if (result.getMessage() == null) {
                result.setMessage(caption);
            }
        }

        if (step.getContentSid() != null && !step.getContentSid().isBlank()) {
            result.setContentSid(step.getContentSid());
            result.setContentVariables(TemplateUtil.renderMap(step.getContentVariables(), context));
        }
        return result;
    }

    private FlowStepResult renderInvalidInput(FlowStep step, Map<String, Object> context) {
        FlowStepResult result = renderStep(step, context);
        result.setMessage("Invalid input. Try again.\n" + result.getMessage());
        return result;
    }

    private String formatOptions(Map<String, String> options) {
        StringBuilder formatted = new StringBuilder();
        options.forEach((key, value) -> formatted.append(key).append(". ").append(value).append("\n"));
        return formatted.toString().stripTrailing();
    }

    private String sessionKey(String orgId, String userId) {
        return orgId + "|" + userId;
    }
}
