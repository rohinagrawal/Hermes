package com.flauntik.service.action;

import io.vertx.core.Future;

import java.util.Map;

/**
 * A named piece of in-process business logic that a flow's ACTION step can invoke
 * (reserve a slot, check a DB, run a rule, call an internal service, compute something) —
 * the in-process counterpart to the HTTP-based API_CALL step.
 *
 * Handlers are registered by name in a Guice {@code MapBinder<String, StepActionHandler>}
 * (see {@code HermesModule}); a flow references one by that name via
 * {@code {"type": "action", "action": "<name>", ...}}. Flow authors pick a handler by
 * name in JSON; developers implement and register the handler here in code — arbitrary
 * Java can't (and shouldn't) be injected from config.
 *
 * <p>Contract: {@code execute} returns a {@code Future} of variables to merge into the
 * user's context (usable as {@code {{var}}} downstream). Signal a business-level failure
 * (so the step takes its {@code failure} branch) either by putting
 * {@code ACTION_SUCCESS=false} in the returned map, or by failing the Future. Anything
 * else is treated as success.
 */
public interface StepActionHandler {

    /** Optional Boolean in the returned map; {@code false} routes the ACTION step to its failure branch. */
    String ACTION_SUCCESS = "_actionSuccess";
    /** Populated by the engine with the error message when a handler fails. */
    String ACTION_ERROR = "_actionError";

    Future<Map<String, Object>> execute(Map<String, String> params, Map<String, Object> context);
}
