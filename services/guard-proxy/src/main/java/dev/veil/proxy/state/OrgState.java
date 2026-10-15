package dev.veil.proxy.state;

import dev.veil.contracts.OrgConfig;
import dev.veil.detect.ScanEngine;

import java.util.Map;

/**
 * Everything compiled from one configuration snapshot: the detection engine (built-in detectors + the
 * organisation's dictionary + semantic detector with its own topics) and decrypted provider keys (memory only).
 */
public record OrgState(OrgConfig config, ScanEngine engine, ScanEngine engineWithoutSemantic, Map<String, String> providerKeys) {

    public long version() {
        return config.version();
    }
}
