package dev.veil.proxy.config;

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
    NewTopic configTopic() {
        return TopicBuilder.name(Topics.CONFIG).partitions(3).replicas(1).compact().build();
    }

    @Bean
    NewTopic keysTopic() {
        return TopicBuilder.name(Topics.KEYS).partitions(3).replicas(1).config("retention.ms", "86400000").build();
    }

    @Bean
    NewTopic auditTopic() {
        return TopicBuilder.name(Topics.AUDIT).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic auditDlqTopic() {
        return TopicBuilder.name(Topics.AUDIT_DLQ).partitions(1).replicas(1).build();
    }

    /**
     * Config and key events must reach EVERY proxy instance, so each instance consumes with its own group id
     * (broadcast, not work-sharing). Config starts from the earliest offset of the compacted topic to rebuild
     * state; key events only matter from now on.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> broadcastFactory(ConsumerFactory<?, ?> defaults, ProxyProperties proxy) {
        // Start from Boot's consumer settings (they include the resolved bootstrap servers).
        Map<String, Object> config = new java.util.HashMap<>(defaults.getConfigurationProperties());
        String instance = proxy.instanceId() == null || proxy.instanceId().isBlank() ? UUID.randomUUID().toString() : proxy.instanceId();
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "guard-proxy-" + instance + "-" + UUID.randomUUID().toString().substring(0, 8));
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        return factory;
    }
}
