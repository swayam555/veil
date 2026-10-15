package dev.veil.detect;

/**
 * One thing a detector found. For document-level types (source code, confidential topic, prompt injection)
 * {@code start} and {@code end} cover the passage that triggered it and {@code value} is a short excerpt.
 *
 * @param confidence 0..1, after validation and context scoring
 * @param detector   which detector produced it, e.g. {@code pattern:aadhaar}
 * @param detail     human-readable sub-type, e.g. "AWS access key" or "Unreleased financial results"
 */
public record Finding(EntityType type, int start, int end, String value, double confidence, String detector,
                      String detail) {

    public Finding {
        if (start < 0 || end < start) throw new IllegalArgumentException("Bad span " + start + ".." + end);
        confidence = Math.max(0, Math.min(1, confidence));
    }

    public int length() {
        return end - start;
    }

    public boolean overlaps(Finding o) {
        return start < o.end && o.start < end;
    }

    public Finding withConfidence(double c) {
        return new Finding(type, start, end, value, c, detector, detail);
    }

    /** Shifts the span, for findings computed on a slice of a larger text. */
    public Finding shift(int offset) {
        return new Finding(type, start + offset, end + offset, value, confidence, detector, detail);
    }
}
