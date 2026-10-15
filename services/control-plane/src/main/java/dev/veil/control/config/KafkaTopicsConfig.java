package dev.veil.control.config;

import dev.veil.contracts.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Every service declares the topics it touches with identical settings (broker auto-create is off). */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    NewTopic configTopic() {
        // Compacted: the latest snapshot per organisation is kept forever, so a new proxy can rebuild its state.
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
}
