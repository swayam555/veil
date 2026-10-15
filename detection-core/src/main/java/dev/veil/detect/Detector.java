package dev.veil.detect;

import java.util.List;

/** Finds sensitive data in text. Implementations must be thread-safe and side-effect free. */
public interface Detector {

    String name();

    List<Finding> detect(String text);
}
