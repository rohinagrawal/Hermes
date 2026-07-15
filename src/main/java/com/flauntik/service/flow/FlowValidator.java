package com.flauntik.service.flow;

import com.flauntik.pojo.FlowStep;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Log4j2
public class FlowValidator {

    /**
     * Validates one org's flow graph, collecting every error found rather than
     * failing on the first, so a bad flow.json is one loud startup report instead
     * of a runtime NPE for whichever user happens to hit the broken step.
     */
    public List<String> validate(String orgId, Map<String, FlowStep> flow) {
        List<String> errors = new ArrayList<>();
        if (flow == null || flow.isEmpty()) {
            errors.add("[" + orgId + "] flow is empty");
            return errors;
        }
        if (!flow.containsKey("start")) {
            errors.add("[" + orgId + "] missing required 'start' step");
        }

        for (Map.Entry<String, FlowStep> entry : flow.entrySet()) {
            String stepId = entry.getKey();
            FlowStep step = entry.getValue();
            String prefix = "[" + orgId + "." + stepId + "] ";

            if (step.getType() == null) {
                errors.add(prefix + "missing or unrecognized 'type'");
                continue;
            }

            switch (step.getType()) {
                case LIST, BUTTON -> {
                    if (Objects.isNull(step.getOptions()) || step.getOptions().isEmpty()) {
                        errors.add(prefix + "type " + step.getType() + " requires non-empty 'options'");
                    }
                    validateNextIsMap(prefix, step, errors);
                    if (step.getNext() instanceof Map) {
                        validateNextTargets(prefix, flow, errors, plainNextTargets(step));
                        validateOptionsMatchNext(prefix, step, errors);
                    }
                }
                case MESSAGE -> validateNextTargets(prefix, flow, errors, plainNextTargets(step));
                case API_CALL -> {
                    if (isBlank(step.getApiUrl())) {
                        errors.add(prefix + "API_CALL requires 'apiUrl'");
                    }
                    validateNextTargets(prefix, flow, errors, plainNextTargets(step));
                }
                case PAYMENT -> {
                    if (isBlank(step.getPaymentAmount())) {
                        errors.add(prefix + "PAYMENT requires 'paymentAmount'");
                    }
                    validateNextTargets(prefix, flow, errors, plainNextTargets(step));
                }
                case MEDIA -> {
                    if (isBlank(step.getMediaType()) || isBlank(step.getMediaUrl())) {
                        errors.add(prefix + "MEDIA requires 'mediaType' and 'mediaUrl'");
                    }
                    validateNextTargets(prefix, flow, errors, plainNextTargets(step));
                }
                case UNKNOWN -> errors.add(prefix + "unrecognized step type");
            }
        }
        return errors;
    }

    @SuppressWarnings("unchecked")
    private void validateOptionsMatchNext(String prefix, FlowStep step, List<String> errors) {
        Map<String, String> next = (Map<String, String>) step.getNext();
        for (String optionKey : step.getOptions().keySet()) {
            if (!next.containsKey(optionKey)) {
                errors.add(prefix + "option '" + optionKey + "' has no matching entry in 'next'");
            }
        }
    }

    private void validateNextIsMap(String prefix, FlowStep step, List<String> errors) {
        if (!(step.getNext() instanceof Map)) {
            errors.add(prefix + "type " + step.getType() + " requires 'next' to be an option->step map");
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> plainNextTargets(FlowStep step) {
        Object next = step.getNext();
        List<String> targets = new ArrayList<>();
        if (next instanceof String s) {
            targets.add(s);
        } else if (next instanceof Map) {
            targets.addAll(((Map<String, String>) next).values());
        }
        return targets;
    }

    private void validateNextTargets(String prefix, Map<String, FlowStep> flow, List<String> errors, List<String> targets) {
        if (targets.isEmpty()) {
            errors.add(prefix + "missing 'next'");
            return;
        }
        for (String target : targets) {
            if (isBlank(target)) {
                errors.add(prefix + "'next' contains a blank target");
            } else if (!flow.containsKey(target)) {
                errors.add(prefix + "'next' points at undefined step '" + target + "'");
            }
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
