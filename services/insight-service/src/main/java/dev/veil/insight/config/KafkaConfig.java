package dev.veil.insight.config;

import dev.veil.contracts.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.Map;
import java.util.UUID;

@Configuration
public class KafkaConfig {

    @Bean
    NewTopic auditTopic() {
        return TopicBuilder.name(Topics.AUDIT).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic auditDlqTopic() {
        return TopicBuilder.name(Topics.AUDIT_DLQ).partitions(1).replicas(1).build();
    }

    /**
     * Storage: one consumer group shared by all insight-service replicas, batches of up to 500 events, so throughput
     * scales with partitions and replicas. Offsets are committed after the batch's database transaction.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> auditBatchFactory(ConsumerFactory<?, ?> defaults) {
        Map<String, Object> config = new java.util.HashMap<>(defaults.getConfigurationProperties());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "insight-service");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setBatchListener(true);
        factory.setConcurrency(3);
        return factory;
    }

    /** Live feed: every replica sees every event (its own group, from now on) to push to its connected browsers. */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> liveFactory(ConsumerFactory<?, ?> defaults) {
        Map<String, Object> config = new java.util.HashMap<>(defaults.getConfigurationProperties());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "insight-live-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        return factory;
    }
}
