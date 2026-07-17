package com.flauntik.service.kafka;

import com.google.inject.Singleton;

/** Bound when {@code HermesConfig.kafka} is absent - no events are published. */
@Singleton
public class NoopFlowEventPublisher implements FlowEventPublisher {

    @Override
    public void publish(String tenantId, String userId, String resultingStepId) {
    }
}
