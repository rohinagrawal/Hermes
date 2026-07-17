package com.flauntik.service.kafka;

/**
 * Publishes a flow-completion event after each processed message, for analytics/audit.
 * Fire-and-forget by design - {@code FlowManager} calls this once per {@code getNextStep}
 * completion and never waits on or fails because of it. Bound to {@code NoopFlowEventPublisher}
 * when {@code HermesConfig.kafka} is absent, or {@code KafkaFlowEventPublisher} when present -
 * see {@code HermesModule}.
 */
public interface FlowEventPublisher {

    void publish(String tenantId, String userId, String resultingStepId);
}
