package com.flauntik.service;

import com.flauntik.enums.FlowStepType;
import com.flauntik.pojo.ConversationSession;
import com.flauntik.pojo.FlowStep;
import com.flauntik.pojo.FlowStepResult;
import com.flauntik.pojo.payment.PaymentRequest;
import com.flauntik.service.action.StepActionHandler;
import com.flauntik.service.flow.OrgFlowRegistry;
import com.flauntik.service.payment.PaymentProvider;
import com.flauntik.service.session.SessionStore;
import com.flauntik.util.TemplateUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.vertx.core.Future;
import lombok.extern.log4j.Log4j2;

import java.util.Map;

/**
 * Drives a per-org, per-user conversation through that org's flow graph.
 *
 * MESSAGE/LIST/BUTTON/MEDIA are "interactive" steps: they're rendered and sent to the
 * user, then execution pauses at that step id until the user's next message arrives.
 * API_CALL/PAYMENT/BRANCH are "automatic" steps: they run immediately (no user input
 * needed) and cascade straight through to the next step.
 *
 * Per-user state + accumulated answers live in a {@code ConversationSession} held by
 * {@code SessionStore} (5 min idle TTL + LRU cap) — an expired/evicted user simply
 * restarts at `start` on their next message.
 */
@Log4j2
@Singleton
public class FlowManager {

    private final OrgFlowRegistry orgFlowRegistry;
    private final ApiCallService apiCallService;
    private final PaymentProvider paymentProvider;
    private final LlmAgentService llmAgentService;
    private final SessionStore sessionStore;
    private final Map<String, StepActionHandler> actionHandlers;

    @Inject
    public FlowManager(OrgFlowRegistry orgFlowRegistry, ApiCallService apiCallService, PaymentProvider paymentProvider,
                       LlmAgentService llmAgentService, SessionStore sessionStore,
                       Map<String, StepActionHandler> actionHandlers) {
        this.orgFlowRegistry = orgFlowRegistry;
        this.apiCallService = apiCallService;
        this.paymentProvider = paymentProvider;
        this.llmAgentService = llmAgentService;
        this.sessionStore = sessionStore;
        this.actionHandlers = actionHandlers;
    }

    public Future<FlowStepResult> getNextStep(String orgId, String userId, String input) {
        Map<String, FlowStep> flow = orgFlowRegistry.getFlow(orgId);
        ConversationSession session = sessionStore.get(orgId, userId);
        Map<String, Object> context = session.getContext();

        String currentStateId = session.getCurrentStepId();
        if (currentStateId == null) {
            // Brand new (or expired/evicted) session: the user hasn't been shown anything
            // yet, so their first message is a trigger to render `start`, not an answer to it.
            return cascade(orgId, userId, session, "start", flow, context);
        }

        FlowStep currentStep = flow.get(currentStateId);
        if (currentStep == null) {
            log.warn("org={} user={} was parked on undefined step '{}', resetting to start", orgId, userId, currentStateId);
            session.setCurrentStepId(null);
            return cascade(orgId, userId, session, "start", flow, context);
        }

        context.put(currentStateId, input);

        return switch (currentStep.getType()) {
            case LIST, BUTTON -> {
                @SuppressWarnings("unchecked")
                Map<String, String> nextMap = (Map<String, String>) currentStep.getNext();
                String nextId = nextMap.get(input);
                yield nextId == null
                        ? handleUnmatchedInput(orgId, currentStep, input, context)
                        : cascade(orgId, userId, session, nextId, flow, context);
            }
            case MESSAGE, MEDIA -> cascade(orgId, userId, session, (String) currentStep.getNext(), flow, context);
            default -> {
                log.warn("org={} user={} was parked on non-interactive step '{}', resetting to start", orgId, userId, currentStateId);
                session.setCurrentStepId(null);
                yield cascade(orgId, userId, session, "start", flow, context);
            }
        };
    }

    private Future<FlowStepResult> cascade(String orgId, String userId, ConversationSession session, String stepId, Map<String, FlowStep> flow, Map<String, Object> context) {
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
                session.setCurrentStepId(resolvedStepId);
                yield Future.succeededFuture(renderStep(resolvedStep, context));
            }
            case API_CALL -> apiCallService.execute(resolvedStep, context).compose(extracted -> {
                context.putAll(extracted);
                boolean success = Boolean.TRUE.equals(extracted.get(ApiCallService.CONTEXT_API_CALL_SUCCESS));
                return cascade(orgId, userId, session, resolveBranchNext(resolvedStep, success), flow, context);
            });
            case PAYMENT -> executePayment(orgId, userId, session, resolvedStep, flow, context);
            case ACTION -> executeAction(orgId, userId, session, resolvedStep, flow, context);
            case BRANCH -> cascade(orgId, userId, session, resolveBranchStep(resolvedStep, context), flow, context);
            default -> Future.failedFuture(new IllegalStateException("Unsupported step type " + step.getType() + " for step " + resolvedStepId));
        };
    }

    /**
     * Runs a named in-process handler (registered in HermesModule's action MapBinder),
     * merges whatever it returns into the user's context, and cascades on the outcome —
     * the in-process twin of {@code executePayment}/API_CALL. A handler signals failure by
     * returning {@code ACTION_SUCCESS=false} or failing its Future; either takes the
     * step's `failure` branch. The handler name is checked at startup by FlowValidator, so
     * a missing handler here is a guarded should-never-happen.
     */
    private Future<FlowStepResult> executeAction(String orgId, String userId, ConversationSession session, FlowStep step, Map<String, FlowStep> flow, Map<String, Object> context) {
        StepActionHandler handler = actionHandlers.get(step.getAction());
        if (handler == null) {
            log.error("org={} flow references unregistered action '{}'", orgId, step.getAction());
            context.put(StepActionHandler.ACTION_ERROR, "unregistered action: " + step.getAction());
            return cascade(orgId, userId, session, resolveBranchNext(step, false), flow, context);
        }

        Map<String, String> params = TemplateUtil.renderMap(step.getActionParams(), context);
        return handler.execute(params, context)
                .compose(result -> {
                    if (result != null) {
                        context.putAll(result);
                    }
                    boolean success = result == null || !Boolean.FALSE.equals(result.get(StepActionHandler.ACTION_SUCCESS));
                    return cascade(orgId, userId, session, resolveBranchNext(step, success), flow, context);
                })
                .recover(err -> {
                    log.error("ACTION '{}' failed for org={} user={}", step.getAction(), orgId, userId, err);
                    context.put(StepActionHandler.ACTION_ERROR, err.getMessage());
                    return cascade(orgId, userId, session, resolveBranchNext(step, false), flow, context);
                });
    }

    /**
     * Evaluates a BRANCH step's rules against the user's accumulated context and returns
     * the next step id. Each rule's {@code when} is templated (so it can reference an
     * answer from any earlier step, e.g. {@code {{patient_type}}}) and compared to
     * {@code equals}; first match wins. If nothing matches, the step's default
     * {@code next} is used. This is how a choice made early changes the flow much later.
     */
    private String resolveBranchStep(FlowStep step, Map<String, Object> context) {
        if (step.getBranches() != null) {
            for (var branch : step.getBranches()) {
                String actual = TemplateUtil.render(branch.getWhen(), context);
                if (actual != null && actual.equals(branch.getEquals())) {
                    return branch.getNext();
                }
            }
        }
        return (String) step.getNext();
    }

    private Future<FlowStepResult> executePayment(String orgId, String userId, ConversationSession session, FlowStep step, Map<String, FlowStep> flow, Map<String, Object> context) {
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
                    return cascade(orgId, userId, session, resolveBranchNext(step, true), flow, context);
                })
                .recover(err -> {
                    log.error("PAYMENT step failed for org={} user={}", orgId, userId, err);
                    context.put("paymentError", err.getMessage());
                    return cascade(orgId, userId, session, resolveBranchNext(step, false), flow, context);
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

    /**
     * The user typed something that isn't a valid menu option. If the org has the LLM
     * fallback enabled, let the agent answer the off-script message and then re-show the
     * menu; otherwise fall back to the canned "invalid input" re-prompt. The user stays
     * parked on the same menu step either way.
     */
    private Future<FlowStepResult> handleUnmatchedInput(String orgId, FlowStep step, String input, Map<String, Object> context) {
        if (!llmAgentService.isAvailableFor(orgId)) {
            return Future.succeededFuture(renderInvalidInput(step, context));
        }

        String menuText = renderStep(step, context).getMessage();
        return llmAgentService.generateReply(orgId, input, menuText)
                .map(reply -> {
                    FlowStepResult result = renderStep(step, context);
                    result.setMessage((reply == null || reply.isBlank())
                            ? "Sorry, I didn't quite get that.\n\n" + menuText
                            : reply + "\n\n" + menuText);
                    return result;
                })
                .otherwise(err -> {
                    log.error("LLM fallback failed for org={}, using default re-prompt", orgId, err);
                    return renderInvalidInput(step, context);
                });
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
}
