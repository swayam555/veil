package dev.veil.detect;

/** Turns text into a unit-length sentence embedding. Implemented by the proxy with an in-process ONNX model. */
@FunctionalInterface
public interface TextEmbedder {

    float[] embed(String text);

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
