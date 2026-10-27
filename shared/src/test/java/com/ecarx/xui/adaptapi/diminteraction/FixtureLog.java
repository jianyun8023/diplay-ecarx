package com.ecarx.xui.adaptapi.diminteraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Static recorder shared by the fake DIM classes and the tests that drive them.
 *
 * The fixture classes in this package reproduce the binary names and signatures that
 * `EcarxDimApi` looks up reflectively on the head unit. Every fake records the calls it
 * receives here, and the payloads handed in (the app's `Proxy` implementations of the
 * `I*Info` interfaces) are read back getter by getter so a test can assert the exact field
 * mapping the cluster service would see.
 */
public final class FixtureLog {
    private static final ArrayList<String> events = new ArrayList<>();
    private static final ArrayList<Call> calls = new ArrayList<>();
    private static final Set<String> failures = new HashSet<>();
    private static final Set<String> once = new HashSet<>();

    public static final class Call {
        public final String method;
        public final Map<String, Object> payload;
        Call(String method, Map<String, Object> payload) {
            this.method = method;
            this.payload = new LinkedHashMap<>(payload);
        }
    }

    public static synchronized void capture(String method, Object... pairs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) payload.put((String) pairs[i], pairs[i + 1]);
        capturePayload(method, payload);
    }

    public static synchronized void capturePayload(String method, Map<String, Object> payload) {
        events.add(method);
        calls.add(new Call(method, payload));
        if (failures.contains(method) || once.remove(method)) {
            throw new IllegalStateException("fixture failure: " + method);
        }
    }

    public static synchronized List<Call> calls() { return new ArrayList<>(calls); }
    public static synchronized void failOn(String method) { failures.add(method); }
    public static synchronized void failOnce(String method) { once.add(method); }
    public static synchronized void clearFailures() { failures.clear(); once.clear(); }

    private FixtureLog() { }

    public static synchronized void record(String event) {
        events.add(event);
    }

    public static synchronized List<String> snapshot() {
        return new ArrayList<>(events);
    }

    public static synchronized void reset() {
        events.clear();
        calls.clear();
        clearFailures();
    }
}
