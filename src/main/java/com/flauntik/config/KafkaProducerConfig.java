package com.flauntik.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class KafkaProducerConfig {
    @JsonProperty("security.protocol")
    protected String securityProtocol;
    @JsonProperty("service.name")
    protected String kafkaServiceName;
    @JsonProperty("number.producers")
    protected Integer numberProducers;
    @JsonProperty("enabled")
    private boolean enabled;
    @JsonProperty("kafka.session.topic")
    private String kafkaTopic;
    @JsonProperty("kafka.session.topic.mmt")
    private String kafkaTopicMMT;
    @JsonProperty("kafka.session.topic.gi")
    private String kafkaTopicGI;
    @JsonProperty("bootstrap.servers")
    private String bootStrapServers;
    @JsonProperty("request.acks")
    private String requestAcks;
    @JsonProperty("message.retries")
    private Integer messageRetries;
    @JsonProperty("batch.size")
    private Integer batchSize;
    @JsonProperty("buffer.memory")
    private Long bufferMemory;
    @JsonProperty("linger.ms")
    private Long lingerInMs;
    @JsonProperty("max.block.ms.hc")
    private Long maxBlockMsHealthCheck;
    @JsonProperty("key.serializer")
    private String keySerializer;
    @JsonProperty("value.serializer")
    private String valueSerializer;
    @JsonProperty("nextpingtime.interval.minutes")
    private int nextPingTimeIntervalInMinutes;

}

