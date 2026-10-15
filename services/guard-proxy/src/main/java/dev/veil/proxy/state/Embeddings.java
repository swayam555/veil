package dev.veil.proxy.state;

import dev.veil.detect.TextEmbedder;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Sentence embeddings computed in-process (all-MiniLM-L6-v2 on ONNX Runtime, 384 dimensions): no API cost, no data
 * leaving the process, works offline.
 *
 * <p>Dynamic batching, the technique GPU inference servers use, applied on CPU: callers put texts on a queue and a few
 * worker threads each take everything that is waiting (up to 32 texts) and embed it in ONE inference. When traffic is
 * light a batch is a single text, so latency is unchanged; under load batches grow and throughput rises instead of
 * latency exploding. The first version ran one inference per text and every inference used every core; at 140
 * requests/s the median embedding took 230 ms. A small LRU skips re-embedding identical passages (system prompts).
 */
@Component
public class Embeddings implements TextEmbedder {

    public static final int DIMENSIONS = 384;
    private static final Logger log = LoggerFactory.getLogger(Embeddings.class);
    private static final int CACHE_SIZE = 4096;

    private record Job(String text, CompletableFuture<float[]> result) {
    }

    private final BatchEncoder encoder;
    private final LinkedBlockingQueue<Job> queue = new LinkedBlockingQueue<>();
    private final List<Thread> workers = new ArrayList<>();
    private final int maxBatch;
    private volatile boolean running = true;
    private final Map<String, float[]> cache = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private final Timer timer;
    private final DistributionSummary batchSizes;

    @Autowired
    public Embeddings(MeterRegistry meters, @org.springframework.beans.factory.annotation.Value("${veil.embedding-model:}") String modelFile) {
        this(meters, defaultWorkers(), defaultThreads(), 32, modelFile == null || modelFile.isBlank() ? null : java.nio.file.Path.of(modelFile));
    }

    public Embeddings(MeterRegistry meters, int workerCount, int threadsPerInference, int maxBatch, java.nio.file.Path modelFile) {
        this.maxBatch = maxBatch;
        if (modelFile != null && !java.nio.file.Files.isReadable(modelFile)) {
            log.warn("Embedding model {} not found; using the FP32 model from the jar", modelFile);
            modelFile = null;
        }
        this.encoder = new BatchEncoder(threadsPerInference, modelFile);
        this.timer = Timer.builder("veil.embedding").description("Embedding time per text, including queueing")
                .publishPercentiles(0.5, 0.95, 0.99).register(meters);
        this.batchSizes = DistributionSummary.builder("veil.embedding.batch").description("Texts per inference")
                .publishPercentiles(0.5, 0.95).register(meters);
        Gauge.builder("veil.embedding.queued", queue, LinkedBlockingQueue::size).register(meters);
        for (int i = 0; i < workerCount; i++) {
            Thread t = Thread.ofPlatform().daemon().name("embed-" + i).start(this::work);
            workers.add(t);
        }
        log.info("Embeddings: {} workers x {} threads, batches up to {}, model {}", workerCount, threadsPerInference, maxBatch,
                modelFile == null ? "FP32 (jar)" : modelFile);
    }

    private static int defaultWorkers() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    }

    private static int defaultThreads() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / defaultWorkers()));
    }

    /** Texts waiting for an inference: the back-pressure signal used for load shedding. */
    public int queued() {
        return queue.size();
    }

    @Override
    public float[] embed(String text) {
        synchronized (cache) {
            float[] hit = cache.get(text);
            if (hit != null) return hit;
        }
        long t0 = System.nanoTime();
        CompletableFuture<float[]> f = new CompletableFuture<>();
        queue.add(new Job(text, f));
        float[] v = f.join();
        timer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
        synchronized (cache) {
            cache.put(text, v);
        }
        return v;
    }

    private void work() {
        List<Job> batch = new ArrayList<>(maxBatch);
        while (running) {
            try {
                Job first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, maxBatch - 1);
                float[][] vectors = encoder.encode(batch.stream().map(Job::text).toList());
                batchSizes.record(batch.size());
                for (int i = 0; i < batch.size(); i++) batch.get(i).result().complete(vectors[i]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                batch.forEach(j -> j.result().completeExceptionally(e));
            } finally {
                batch.clear();
            }
        }
    }

    @PreDestroy
    void close() throws Exception {
        running = false;
        workers.forEach(Thread::interrupt);
        encoder.close();
    }
}
