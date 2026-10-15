package dev.veil.insight.ingest;

import dev.veil.insight.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Keeps daily partitions of the requests table ready three days ahead and drops partitions older than the
 * retention period. Runs before the Kafka consumers start (PostConstruct), then hourly.
 */
@Component
public class Partitions {

    private static final Logger log = LoggerFactory.getLogger(Partitions.class);
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcClient jdbc;
    private final AppProperties props;

    public Partitions(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @PostConstruct
    @Scheduled(cron = "0 5 * * * *")
    public void maintain() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int d = -1; d <= 3; d++) create(today.plusDays(d));
        dropBefore(today.minusDays(Math.max(1, props.retentionDays())));
    }

    private void create(LocalDate day) {
        String name = "requests_" + NAME.format(day);
        try {
            jdbc.sql("CREATE TABLE IF NOT EXISTS " + name + " PARTITION OF requests FOR VALUES FROM ('" + day
                    + " 00:00:00+00') TO ('" + day.plusDays(1) + " 00:00:00+00')").update();
        } catch (RuntimeException e) {
            log.warn("Could not create partition {}: {}", name, e.getMessage());
        }
    }

    private void dropBefore(LocalDate cutoff) {
        var old = jdbc.sql("""
                        SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid JOIN pg_class p ON p.oid = i.inhparent
                        WHERE p.relname = 'requests' AND c.relname ~ '^requests_[0-9]{8}$' AND c.relname < ?""")
                .param("requests_" + NAME.format(cutoff)).query(String.class).list();
        for (String name : old) {
            jdbc.sql("DROP TABLE IF EXISTS " + name).update();
            log.info("Dropped expired partition {}", name);
        }
    }
}
