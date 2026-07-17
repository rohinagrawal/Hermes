package com.flauntik.service.kafka;

import com.flauntik.config.HermesConfig;
import com.flauntik.config.KafkaConfig;
import com.flauntik.util.KafkaUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import lombok.extern.log4j.Log4j2;

/** Bound when {@code HermesConfig.kafka} is present - publishes one event per processed message. */
@Log4j2
@Singleton
public class KafkaFlowEventPublisher implements FlowEventPublisher {

    private final KafkaProducer<String, String> producer;
    private final String eventsTopic;

    @Inject
    @SuppressWarnings("unchecked")
    public KafkaFlowEventPublisher(Vertx vertx, HermesConfig hermesConfig) {
        KafkaConfig kafkaConfig = hermesConfig.getKafka();
        this.eventsTopic = kafkaConfig.getEventsTopic();
        this.producer = (KafkaProducer<String, String>) KafkaUtil.intiaizeKafkaProducer(vertx, kafkaConfig.getProducer());
    }

    /**
     * Partition key is {@code tenantId|userId} - the same key used for incoming-message
     * ingestion - so a tenant's per-user event ordering is consistent with its message
     * ordering, even though this topic is independent of the incoming-messages one.
     */
    @Override
    public void publish(String tenantId, String userId, String resultingStepId) {
        JsonObject event = new JsonObject()
                .put("tenantId", tenantId)
                .put("userId", userId)
                .put("resultingStepId", resultingStepId)
                .put("ts", System.currentTimeMillis());

        String partitionKey = tenantId + "|" + userId;
        producer.send(KafkaProducerRecord.create(eventsTopic, partitionKey, event.encode()), ar -> {
            if (ar.failed()) {
                log.warn("Failed to publish flow-completion event for tenant={} user={}: {}", tenantId, userId, ar.cause().getMessage());
            }
        });
    }
}
