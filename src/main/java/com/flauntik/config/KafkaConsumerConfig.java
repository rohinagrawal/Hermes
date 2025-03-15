package com.flauntik.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KafkaConsumerConfig {

    @JsonProperty("autoOffSetResetConfig")
    public String autoOffSetResetConfig;
    @JsonProperty("consumers.topics.list")
    protected Set<String> consumerTopics;
    @JsonProperty("bootstrap.servers")
    protected String bootStrapServers;
    @JsonProperty("enable.auto.commit")
    protected Boolean enableAutoCommit;
    //Not used
    @JsonProperty("message.retries")
    protected Integer messageRetries;
    @JsonProperty("batch.size")
    protected Integer batchSize;
    @JsonProperty("session.timeout.ms")
    protected Integer sessionTimeOutMs;
    @JsonProperty("key.deserializer")
    protected String keyDeserializer;
    @JsonProperty("value.deserializer")
    protected String valueDeserializer;
    @JsonProperty("number.consumers")
    protected Integer numConsumers;
    @JsonProperty("poll.interval.millis")
    protected Integer pollIntervalInMillis;
    @JsonProperty("max.poll.interval.millis")
    protected Integer maxPollIntervalInMillis;
    @JsonProperty("sleep.time.increase.rate.perCent")
    protected Integer sleepTimeIncreaseRateInPerCent;
    @JsonProperty("max.sleep.time.millis")
    protected Integer maxSleepTimeInMillis;
    @JsonProperty("request.timeout.ms.hc")
    protected Integer requestTimeoutMsHealthCheck;
    @JsonProperty("session.timeout.ms.hc")
    protected Integer sessionTimeoutMsHealthCheck;
    @JsonProperty("heartbeat.timeout.ms.hc")
    protected Integer heartbeatTimeoutMsHealthCheck;
    @JsonProperty("security.protocol")
    protected String securityProtocol;
    @JsonProperty("service.name")
    protected String kafkaServiceName;
    @JsonProperty("static.group.id")
    protected String staticGroupId;
    @JsonProperty("max.poll.records")
    protected Integer maxPollRecords;
    @JsonProperty("heartbeat.interval.ms")
    protected Integer heartbeatIntervalInMs;
}
