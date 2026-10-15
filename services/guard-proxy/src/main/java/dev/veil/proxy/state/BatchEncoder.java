package dev.veil.proxy.state;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs all-MiniLM-L6-v2 on a batch of texts in a single ONNX inference: tokenise, pad to the longest text in the
 * batch, run the transformer once, mean-pool each row over its real tokens and L2-normalise. On CPU a batch of 16
 * short texts costs far less than 16 separate runs, which is what lets the proxy keep up under load.
 */
final class BatchEncoder implements AutoCloseable {

    static final int MAX_TOKENS = 256;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final boolean hasTypeIds;

    /** @param modelFile an alternative ONNX file (e.g. the INT8-quantised build); null uses the FP32 model from the jar */
    BatchEncoder(int intraOpThreads, java.nio.file.Path modelFile) {
        try (InputStream onnx = modelFile != null ? java.nio.file.Files.newInputStream(modelFile) : resource("/all-minilm-l6-v2.onnx");
             InputStream tok = resource("/all-minilm-l6-v2-tokenizer.json")) {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(intraOpThreads);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = env.createSession(onnx.readAllBytes(), options);
            Map<String, String> cfg = new HashMap<>();
            cfg.put("padding", "false");
            cfg.put("truncation", "true");
            cfg.put("maxLength", String.valueOf(MAX_TOKENS));
            tokenizer = HuggingFaceTokenizer.newInstance(tok, cfg);
            hasTypeIds = session.getInputNames().contains("token_type_ids");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (OrtException e) {
            throw new IllegalStateException("Could not load the embedding model", e);
        }
    }

    private static InputStream resource(String name) {
        InputStream in = BatchEncoder.class.getResourceAsStream(name);
        if (in == null) throw new IllegalStateException("Missing model resource " + name);
        return in;
    }

    float[][] encode(List<String> texts) {
        Encoding[] enc = tokenizer.batchEncode(texts, true, false);
        int n = enc.length;
        int len = 1;
        for (Encoding e : enc) len = Math.max(len, e.getIds().length);
        long[] ids = new long[n * len];
        long[] mask = new long[n * len];
        long[] types = new long[n * len];
        for (int i = 0; i < n; i++) {
            long[] id = enc[i].getIds();
            long[] m = enc[i].getAttentionMask();
            long[] t = enc[i].getTypeIds();
            System.arraycopy(id, 0, ids, i * len, id.length);
            System.arraycopy(m, 0, mask, i * len, m.length);
            System.arraycopy(t, 0, types, i * len, t.length);
        }
        long[] shape = {n, len};
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape));
            if (hasTypeIds) inputs.put("token_type_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape));
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] hidden = (float[][][]) result.get(0).getValue();
                float[][] out = new float[n][];
                for (int i = 0; i < n; i++) out[i] = meanPool(hidden[i], mask, i * len, len);
                return out;
            }
        } catch (OrtException e) {
            throw new IllegalStateException("Embedding inference failed", e);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    private static float[] meanPool(float[][] tokens, long[] mask, int offset, int len) {
        int dim = tokens[0].length;
        float[] v = new float[dim];
        int count = 0;
        for (int t = 0; t < len; t++) {
            if (mask[offset + t] == 0) continue;
            count++;
            float[] row = tokens[t];
            for (int d = 0; d < dim; d++) v[d] += row[d];
        }
        double norm = 0;
        for (int d = 0; d < dim; d++) {
            v[d] /= Math.max(1, count);
            norm += v[d] * v[d];
        }
        float inv = (float) (1 / Math.max(1e-12, Math.sqrt(norm)));
        for (int d = 0; d < dim; d++) v[d] *= inv;
        return v;
    }

    @Override
    public void close() throws OrtException {
        session.close();
        tokenizer.close();
    }
}
