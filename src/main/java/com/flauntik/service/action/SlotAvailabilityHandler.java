package com.flauntik.service.action;

import com.google.inject.Singleton;
import io.vertx.core.Future;
import lombok.extern.log4j.Log4j2;

import java.util.HashMap;
import java.util.Map;

/**
 * Example ACTION handler: an in-process availability rule. Reads a {@code slot} param and
 * rejects Sundays (the clinic is closed), routing the flow to its failure branch without
 * any external call. A real implementation would hit a scheduling DB/service and return
 * the booked slot id — the flow JSON wouldn't change. Registered as
 * {@code "check_slot_availability"} in {@code HermesModule}.
 */
@Log4j2
@Singleton
public class SlotAvailabilityHandler implements StepActionHandler {

    @Override
    public Future<Map<String, Object>> execute(Map<String, String> params, Map<String, Object> context) {
        String slot = params.getOrDefault("slot", "").toLowerCase();
        boolean closed = slot.contains("sunday") || slot.matches(".*\\bsun\\b.*");

        Map<String, Object> result = new HashMap<>();
        result.put(ACTION_SUCCESS, !closed);
        if (closed) {
            result.put("availabilityNote", "the clinic is closed on Sundays");
            log.info("check_slot_availability: rejected slot '{}' (Sunday)", slot);
        }
        return Future.succeededFuture(result);
    }
}
