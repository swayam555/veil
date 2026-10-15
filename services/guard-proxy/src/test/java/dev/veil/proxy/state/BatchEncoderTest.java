package dev.veil.proxy.state;

import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.veil.detect.TextEmbedder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The batched encoder must produce the same vectors as the reference model, whatever else is in the batch. */
class BatchEncoderTest {

    @Test
    void matchesTheReferenceModelAndPaddingDoesNotChangeResults() throws Exception {
        var reference = new AllMiniLmL6V2EmbeddingModel();
        try (BatchEncoder enc = new BatchEncoder(2, null)) {
            List<String> texts = List.of("Hi", "Customer Priya asked about a refund for order 42, please draft a reply.",
                    "Before the earnings call: Q3 revenue is 412 crore and we will miss guidance by 12 percent. ".repeat(3));
            float[][] batch = enc.encode(texts);
            for (int i = 0; i < texts.size(); i++) {
                float[] single = enc.encode(List.of(texts.get(i)))[0];
                float[] ref = reference.embed(texts.get(i)).content().vector();
                assertThat(TextEmbedder.cosine(batch[i], single)).as("batch vs single: " + i).isGreaterThan(0.9999);
                assertThat(TextEmbedder.cosine(batch[i], ref)).as("vs reference: " + i).isGreaterThan(0.9999);
            }
        }
    }

    @Test
    void concurrentCallersAllGetTheirOwnVector() {
        Embeddings e = new Embeddings(new SimpleMeterRegistry(), 2, 2, 8, null);
        var futures = IntStream.range(0, 64).mapToObj(i -> CompletableFuture.supplyAsync(() -> e.embed("text number " + i))).toList();
        var vectors = futures.stream().map(CompletableFuture::join).toList();
        for (int i = 0; i < 64; i += 9) {
            assertThat(TextEmbedder.cosine(vectors.get(i), e.embed("text number " + i))).isGreaterThan(0.9999);
        }
        assertThat(vectors).allSatisfy(v -> assertThat(v).hasSize(Embeddings.DIMENSIONS));
    }
}
