package com.flauntik.verticle;

import com.flauntik.config.HermesConfig;
import com.flauntik.config.KafkaConfig;
import com.flauntik.service.HermesService;
import com.flauntik.util.KafkaUtil;
import com.google.inject.Inject;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import lombok.extern.log4j.Log4j2;

/**
 * Consumes real-provider webhook payloads that {@code HttpVerticle} produced to
 * {@code KafkaConfig.incomingTopic} (only deployed when {@code HermesConfig.kafka} is
 * present - see {@code MainVerticle}), and drives them through the exact same
 * {@link HermesService#handleIncomingMessage(JsonObject)} that {@code APIVerticle} uses
 * for the synchronous path, so the flow engine doesn't know or care which path a message
 * arrived by.
 *
 * The consumer/connection settings come from the reusable {@code KafkaConsumerConfig} via
 * {@code KafkaUtil}. Records are processed on an <b>ordered</b> blocking executor so a
 * single user's messages (which the {@code tenantId|from} partition key already delivers
 * in order) are also processed in order - the flow engine's per-user state machine
 * depends on it. The per-record offset is committed only after processing (at-least-once):
 * a crash between processing and commit could reprocess one message, an accepted v1
 * tradeoff.
 */
@Log4j2
public class KafkaIngestionVerticle extends AbstractVerticle {

    private final HermesConfig hermesConfig;
    private final HermesService hermesService;
    private KafkaConsumer<String, String> consumer;

    @Inject
    public KafkaIngestionVerticle(HermesConfig hermesConfig, HermesService hermesService) {
        this.hermesConfig = hermesConfig;
        this.hermesService = hermesService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void start(Promise<Void> startPromise) {
        KafkaConfig kafkaConfig = hermesConfig.getKafka();
        consumer = (KafkaConsumer<String, String>) KafkaUtil.initializeKafkaConsumer(vertx, kafkaConfig.getConsumer());

        consumer.handler(record -> vertx.executeBlocking(future -> {
            try {
                JsonObject canonical = new JsonObject(record.value());
                hermesService.handleIncomingMessage(canonical);
            } catch (Exception e) {
                // A poison message must not wedge the consumer forever - log and move on
                // (its offset is still committed below) rather than retry indefinitely.
                log.error("Failed to process Kafka record topic={} partition={} offset={}",
                        record.topic(), record.partition(), record.offset(), e);
            }
            future.complete();
        }, true, ar -> KafkaUtil.commitOffset(consumer, record.topic(), record.partition(), record.offset(), "")));

        consumer.subscribe(kafkaConfig.getIncomingTopic())
                .onSuccess(v -> {
                    log.info("KafkaIngestionVerticle subscribed to topic={} groupId={}",
                            kafkaConfig.getIncomingTopic(), kafkaConfig.getConsumer().getStaticGroupId());
                    startPromise.complete();
                })
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop() {
        if (consumer != null) {
            KafkaUtil.stopKafkaConsumer(consumer);
        }
    }
}
