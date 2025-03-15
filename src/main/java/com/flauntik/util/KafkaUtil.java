package com.flauntik.util;

import com.flauntik.config.KafkaConsumerConfig;
import com.flauntik.config.KafkaProducerConfig;
import io.vertx.core.Vertx;
import io.vertx.kafka.client.common.TopicPartition;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.OffsetAndMetadata;
import io.vertx.kafka.client.producer.KafkaProducer;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.KafkaException;

import java.util.*;

@Log4j2
public class KafkaUtil {

    static String SECURITY_PROTOCOL = "security.protocol";
    static String KERBEROS_SERVICE_NAME = "sasl.kerberos.service.name";

    public static KafkaProducer<?, ?> intiaizeKafkaProducer(Vertx vertx, KafkaProducerConfig kafkaProducerConfig) {
        Properties producerProperties = new Properties();
        if (Objects.isNull(kafkaProducerConfig)) throw new IllegalArgumentException("Null Configs Passed");

        if (StringUtils.isBlank(kafkaProducerConfig.getBootStrapServers())) {
            throw new IllegalArgumentException("Bootstrap server config should not be empty");
        } else {
            producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Arrays.asList(kafkaProducerConfig.getBootStrapServers().split(",")));
        }

        if (StringUtils.isNotBlank(kafkaProducerConfig.getKeySerializer())) {
            producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, kafkaProducerConfig.getKeySerializer());
        } else {
            throw new IllegalArgumentException("Key Serializer value is required");
        }

        if (StringUtils.isNotBlank(kafkaProducerConfig.getValueSerializer())) {
            producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, kafkaProducerConfig.getValueSerializer());
        } else {
            throw new IllegalArgumentException("Value Serializer value is required");
        }

        if (StringUtils.isNotBlank(kafkaProducerConfig.getRequestAcks()))
            producerProperties.put(ProducerConfig.ACKS_CONFIG, kafkaProducerConfig.getRequestAcks());
        if (!Objects.isNull(kafkaProducerConfig.getMessageRetries()))
            producerProperties.put(ProducerConfig.RETRIES_CONFIG, kafkaProducerConfig.getMessageRetries());
        if (!Objects.isNull(kafkaProducerConfig.getBatchSize()))
            producerProperties.put(ProducerConfig.BATCH_SIZE_CONFIG, kafkaProducerConfig.getBatchSize());
        if (!Objects.isNull(kafkaProducerConfig.getBufferMemory()))
            producerProperties.put(ProducerConfig.BUFFER_MEMORY_CONFIG, kafkaProducerConfig.getBufferMemory());
        if (!Objects.isNull(kafkaProducerConfig.getLingerInMs()))
            producerProperties.put(ProducerConfig.LINGER_MS_CONFIG, kafkaProducerConfig.getLingerInMs());
        if (StringUtils.isNotBlank(kafkaProducerConfig.getSecurityProtocol()))
            producerProperties.put(SECURITY_PROTOCOL, kafkaProducerConfig.getSecurityProtocol());
        if (StringUtils.isNotBlank(kafkaProducerConfig.getKafkaServiceName()))
            producerProperties.put(KERBEROS_SERVICE_NAME, kafkaProducerConfig.getKafkaServiceName());
        return KafkaProducer.create(vertx, producerProperties);

    }

    public static KafkaConsumer<?, ?> initializeKafkaConsumer(Vertx vertx, KafkaConsumerConfig kafkaConsumerConfig) {
        Properties props = new Properties();
        if (Objects.isNull(kafkaConsumerConfig)) throw new IllegalArgumentException("Null Configs Passed");

        if (StringUtils.isNotBlank(kafkaConsumerConfig.getBootStrapServers())) {
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConsumerConfig.getBootStrapServers());
        } else
            throw new IllegalArgumentException(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG + " value is required");

        if (StringUtils.isNotBlank(kafkaConsumerConfig.getKeyDeserializer()))
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, kafkaConsumerConfig.getKeyDeserializer());
        else
            throw new IllegalArgumentException(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG + " value is required");

        if (StringUtils.isNotBlank(kafkaConsumerConfig.getValueDeserializer()))
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, kafkaConsumerConfig.getValueDeserializer());
        else
            throw new IllegalArgumentException(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG + " value is required");

        if (StringUtils.isNotBlank(kafkaConsumerConfig.getStaticGroupId()))
            props.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaConsumerConfig.getStaticGroupId());
        else
            throw new IllegalArgumentException(ConsumerConfig.GROUP_ID_CONFIG + " value is required");

        if (!Objects.isNull(kafkaConsumerConfig.getEnableAutoCommit()))
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, kafkaConsumerConfig.getEnableAutoCommit());

        if (!Objects.isNull(kafkaConsumerConfig.getMaxPollIntervalInMillis()))
            props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, kafkaConsumerConfig.getMaxSleepTimeInMillis() + 1000);

        if (StringUtils.isNotBlank(kafkaConsumerConfig.getAutoOffSetResetConfig()))
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, kafkaConsumerConfig.getAutoOffSetResetConfig());

        if (StringUtils.isNotBlank(kafkaConsumerConfig.getSecurityProtocol()))
            props.put(SECURITY_PROTOCOL, kafkaConsumerConfig.getSecurityProtocol());

        if (StringUtils.isNotBlank(kafkaConsumerConfig.getKafkaServiceName()))
            props.put(KERBEROS_SERVICE_NAME, kafkaConsumerConfig.getKafkaServiceName());

        return KafkaConsumer.create(vertx, props);
    }

    public static void stopKafkaConsumer(KafkaConsumer<?, ?> kafkaConsumer) {
        kafkaConsumer.close();
    }

    public static void commitOffset(KafkaConsumer<?, ?> consumer) {
        consumer.commit(ar -> {
            if (ar.succeeded()) {
                log.debug("Last read message offset committed ");
            } else {
                log.error("Unable to commit offset for last read message");
                throw new KafkaException(ar.cause());
            }
        });
    }

    public static void commitOffset(KafkaConsumer<?, ?> consumer, String topic, int partition, long offset, String metadata) {
        TopicPartition topicPartition = new TopicPartition(topic, partition);
        OffsetAndMetadata offsetAndMetadata = new OffsetAndMetadata(offset + 1, metadata);
        Map<TopicPartition, OffsetAndMetadata> partitionOffsetAndMetadataMap = new HashMap<>();
        partitionOffsetAndMetadataMap.put(topicPartition, offsetAndMetadata);
        consumer.commit(partitionOffsetAndMetadataMap, ar -> {
            if (ar.succeeded()) {
                log.debug("Last read message offset committed topic:" + topic + ", Partition:" + partition + ", offset:" + offset + ", metadata:" + metadata);
            } else {
                log.error("Unable to commit offset for last read message topic:" + topic + ", Partition:" + partition + ", offset:" + offset + ", metadata:" + metadata, ar.cause());
                throw new KafkaException(ar.cause());
            }
        });
    }

}
