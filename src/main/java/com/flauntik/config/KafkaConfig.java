package com.flauntik.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Optional Kafka settings for async ingestion of real-provider webhooks (Twilio/Meta) and
 * flow-completion event publishing. Absent from {@code HermesConfig} entirely means both
 * stay disabled - webhooks dispatch synchronously over the event bus exactly as before
 * this was added, and no events are published. See {@code verticle.KafkaIngestionVerticle}
 * and {@code service.kafka.FlowEventPublisher}.
 *
 * The actual producer/consumer connection settings live in the reusable
 * {@link KafkaProducerConfig}/{@link KafkaConsumerConfig} (built into clients via
 * {@code util.KafkaUtil}); this wrapper only adds the two Hermes-domain topic names - the
 * topic the webhook ingestion produces to / the consumer subscribes to, and the topic
 * flow-completion events are published to.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KafkaConfig {

    @JsonProperty("incomingTopic")
    private String incomingTopic = "hermes.incoming-messages";

    @JsonProperty("eventsTopic")
    private String eventsTopic = "hermes.flow-events";

    @JsonProperty("producer")
    private KafkaProducerConfig producer;

    @JsonProperty("consumer")
    private KafkaConsumerConfig consumer;
}
