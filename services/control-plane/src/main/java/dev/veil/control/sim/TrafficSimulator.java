package dev.veil.control.sim;

import dev.veil.control.config.AppProperties;
import dev.veil.control.seed.DemoSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends realistic traffic through the real proxy with the demo keys: apps calling /v1/chat/completions and
 * browser extensions reporting pastes and site visits. Everything goes through the same code path as real use.
 */
@Component
public class TrafficSimulator {

    private static final Logger log = LoggerFactory.getLogger(TrafficSimulator.class);

    private final RestClient proxy;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Random random = new Random();
    private final SyntheticData data = new SyntheticData(random);
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong ok = new AtomicLong();
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong limited = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private volatile ScheduledFuture<?> task;
    private volatile int perMinute;
    private volatile Instant startedAt;

    public TrafficSimulator(AppProperties props) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.proxy = RestClient.builder().baseUrl(props.proxyUrl()).requestFactory(factory).build();
    }

    public record Status(boolean running, int perMinute, long sent, long ok, long blocked, long rateLimited, long errors, Instant startedAt) {
    }

    public synchronized Status start(int requestsPerMinute) {
        int rate = Math.max(6, Math.min(600, requestsPerMinute));
        stop();
        perMinute = rate;
        startedAt = Instant.now();
        long periodMs = 60_000L / rate;
        task = scheduler.scheduleAtFixedRate(() -> workers.submit(this::tick), 0, periodMs, TimeUnit.MILLISECONDS);
        log.info("Traffic simulator started at {} requests/minute", rate);
        return status();
    }

    public synchronized Status stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
            log.info("Traffic simulator stopped after {} requests", sent.get());
        }
        return status();
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
        workers.shutdownNow();
    }

    public Status status() {
        return new Status(task != null, perMinute, sent.get(), ok.get(), blocked.get(), limited.get(), errors.get(), startedAt);
    }

    void tick() {
        try {
            if (random.nextInt(100) < 18) extensionEvent();
            else chat();
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            log.debug("Simulated request failed: {}", e.toString());
        }
    }

    private void chat() {
        PromptCorpus.Scenario s = weighted(PromptCorpus.API);
        String key = DemoSeeder.KEYS.stream().filter(k -> k.kind().equals("API") && k.team().equals(s.team()))
                .findFirst().orElseThrow().secret();
        boolean stream = random.nextInt(4) == 0;
        Map<String, Object> body = Map.of("model", s.model(), "stream", stream, "max_tokens", 300,
                "messages", List.of(Map.of("role", "system", "content", "You are a helpful assistant at Acme Fintech."),
                        Map.of("role", "user", "content", s.prompt().apply(data))));
        send("/v1/chat/completions", key, body);
    }

    private void extensionEvent() {
        var keys = DemoSeeder.KEYS.stream().filter(k -> k.kind().equals("EXTENSION")).toList();
        var k = keys.get(random.nextInt(keys.size()));
        String site = data.pick(PromptCorpus.SITES);
        String user = data.pick(List.of("neha", "arjun", "kavita", "rohan", "meera", "sanjay")) + "@acme.test";
        if (random.nextInt(3) == 0) {
            send("/v1/extension/events", k.secret(), Map.of("kind", "VISIT", "site", site, "user", user));
            return;
        }
        String person = data.person();
        String text = data.pick(List.of(
                "Summarise this complaint from " + person + " (" + data.email(person) + "): the app charged me twice.",
                "Make this email more formal: Hi team, the client Zenith Capital wants the revised quote by Monday.",
                "Our outside counsel says we will likely lose the arbitration with the vendor; draft a settlement note.",
                "Translate to Hindi: your appointment is confirmed for Friday at 4 PM.",
                "Explain this error: NullPointerException at PaymentService.java:42"));
        send("/v1/extension/scan", k.secret(), Map.of("text", text, "site", site, "kind", "PASTE", "user", user));
    }

    private void send(String path, String key, Map<String, Object> body) {
        sent.incrementAndGet();
        try {
            proxy.post().uri(path).header("Authorization", "Bearer " + key).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().toBodilessEntity();
            ok.incrementAndGet();
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code == 403) blocked.incrementAndGet();
            else if (code == 429) limited.incrementAndGet();
            else errors.incrementAndGet();
        }
    }

    private PromptCorpus.Scenario weighted(List<PromptCorpus.Scenario> list) {
        int total = list.stream().mapToInt(PromptCorpus.Scenario::weight).sum();
        int r = random.nextInt(total);
        for (var s : list) {
            r -= s.weight();
            if (r < 0) return s;
        }
        return list.getLast();
    }
}
